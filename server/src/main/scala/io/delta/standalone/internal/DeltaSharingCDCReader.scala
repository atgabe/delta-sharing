/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Putting these classes in this package to access Delta Standalone internal APIs
package io.delta.standalone.internal

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit

import com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem
import com.google.common.cache.CacheBuilder
import com.google.common.hash.Hashing
import io.delta.standalone.DeltaLog
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.azure.NativeAzureFileSystem
import org.apache.hadoop.fs.azurebfs.AzureBlobFileSystem
import org.apache.hadoop.fs.s3a.S3AFileSystem
import org.apache.spark.sql.types.{DataType, MetadataBuilder, StructType}

import io.delta.sharing.server.{model, AbfsFileSigner, GCSFileSigner, S3FileSigner, WasbFileSigner}
import io.delta.sharing.server.config.{ServerConfig, TableConfig}

/**
 * A class to load Delta tables from `TableConfig`. It also caches the loaded tables internally
 * to speed up the loading.
 */
class DeltaSharingCDCReader(val deltaLog: DeltaLogImpl) {

  private lazy val snapshot = deltaLog.snapshot
  lazy val protocol = snapshot.protocolScala
  lazy val metadata = snapshot.metadataScala
  private lazy val history = DeltaHistoryManager(deltaLog)

  @VisibleForTesting
  private[internal] def getCDCVersions(
      CdfOptions: Map[String, String],
      latestVersion: Long): (Long, Long) = {
    val startingVersion = getVersionForCDC(
      CdfOptions,
      DeltaDataSource.CDF_START_VERSION_KEY,
      DeltaDataSource.CDF_START_TIMESTAMP_KEY,
      latestVersion
    )
    if (startingVersion.isEmpty) {
      throw DeltaCDFErrors.noStartVersionForCDF()
    }
    // add a version check here that is cheap instead of after trying to list a large version
    // that doesn't exist
    if (startingVersion.get > latestVersion) {
      throw DeltaCDFErrors.startVersionAfterLatestVersion(startingVersion.get, latestVersion)
    }

    val endingVersion = getVersionForCDC(
      CdfOptions,
      DeltaDataSource.CDF_END_VERSION_KEY,
      DeltaDataSource.CDF_END_TIMESTAMP_KEY,
      latestVersion
    )

    if (endingVersion.exists(_ < startingVersion.get)) {
      throw DeltaCDFErrors.endBeforeStartVersionInCDF(startingVersion.get, endingVersion.get)
    }

    (startingVersion.get, endingVersion.getOrElse(latestVersion))
  }

  // Convert timestamp string in CdfOptions to Timestamp
  private def getTimestamp(paramName: String, timeStampStr: String): Timestamp = {
    TimeZone.setDefault(snapshot.readTimeZone)
    try {
      Timestamp.valueOf(timeStampStr)
    } catch {
      case e: IllegalArgumentException =>
        throw DeltaCDFErrors.invalidTimestamp(paramName, e.getMessage)
    }
  }

  /**
   * - If a commit version exactly matches the provided timestamp, we return it.
   * - Otherwise, we return the earliest commit version
   *   with a timestamp greater than the provided one.
   * - If the provided timestamp is larger than the timestamp
   *   of any committed version, we throw an error.
   *
   * @param timestamp - user specified timestamp
   * @param latestVersion - latest version of the table
   * @return - corresponding version number for timestamp
   */
  private def getStartingVersionFromTimestamp(timestamp: Timestamp, latestVersion: Long): Long = {
    val commit = history.getActiveCommitAtTime(timestamp)
    if (commit.timestamp >= timestamp.getTime) {
      // Find the commit at the `timestamp` or the earliest commit
      commit.version
    } else {
      // commit.timestamp is not the same, so this commit is a commit before the timestamp and
      // the next version if exists should be the earliest commit after the timestamp.
      // Note: `getActiveCommitAtTime` has called `update`, so we don't need to call it again.
      if (commit.version + 1 <= latestVersion) {
        commit.version + 1
      } else {
        val commitTs = new Timestamp(commit.timestamp)
        throw DeltaErrors.timestampLaterThanTableLastCommit(timestamp, commitTs)
      }
    }
  }

  /**
   * Given timestamp or version this method returns the corresponding version for that timestamp
   * or the version itself.
   */
  private def getVersionForCDC(
      options: Map[String, String],
      versionKey: String,
      timestampKey: String,
      latestVersion: Long
  ): Option[Long] = {
    if (options.contains(versionKey)) {
      Some(options(versionKey).toLong)
    } else if (options.contains(timestampKey)) {
      val tsStr = options(timestampKey)
      if (timestampKey == DeltaDataSource.CDF_START_TIMESTAMP_KEY) {
        // For the starting timestamp we need to find a version after the provided timestamp
        // we can use the same semantics as streaming.
        val ts = getTimestamp("startingTimestamp", tsStr)
        val resolvedVersion = getStartingVersionFromTimestamp(ts, latestVersion)
        Some(resolvedVersion)
      } else {
        // For ending timestamp the version should be before the provided timestamp.
        val ts = getTimestamp("endingTimestamp", tsStr)
        Some(history.getActiveCommitAtTime(ts).version)
      }
    } else {
      None
    }
  }

  /**
   * Replay Delta transaction logs and return cdf files
   *
   * @param CdfOptions to indicate the starting and ending version of the change data feed.
   * @param latestVersion the latest version of the delta table, which is used to validate the
   *                      starting and ending versions, and may be used as default ending version.
   * @param fileListener a listener that processes `version, timestamp, fileAction`s. Delta sharing
   *                     server process these to return pre-sign urls for cdf files. Timestamp is
   *                     number of milliseconds from epoch
   */
  def replay(CdfOptions: Map[String, String], latestVersion: Long): Unit = {
    val (start, end) = getCDCVersions(CdfOptions, latestVersion)
    val changes = deltaLog.getChanges(start, false).takeWhile(_._1 <= end)

    // Correct timestamp values are only available through DeltaHistoryManager.getCommits(). Commit
    // info timestamps are wrong, and file modification times are wrong because they need to be
    // monotonized first. This just performs a list (we don't read the contents of the files in
    // getCommits()) so it's not a big deal.
    val timestampsByVersion: Map[Long, Timestamp] = {
      val commits = getCommitsSafe(
        deltaLog.store,
        deltaLog.logPath,
        start,
        end + 1
      )

      // Note that the timestamps come from filesystem modification timestamps, so they're
      // milliseconds since epoch and we don't need to deal with timezones.
      commits.map(f => (f.version -> new Timestamp(f.timestamp))).toMap
    }

    val changeFiles = ListBuffer[CDCDataSpec[AddCDCFile]]()
    val addFiles = ListBuffer[CDCDataSpec[AddFile]]()
    val removeFiles = ListBuffer[CDCDataSpec[RemoveFile]]()

    if (!isCDCEnabledOnTable(deltaLog.getSnapshotForVersionAsOf(start).metadata)) {
      throw DeltaCDFErrors.changeDataNotRecordedException(start, start, end)
    }

    changes.foreach {
      case (v, actions) =>
        // Check whether CDC was newly disabled in this version. (We should have already checked
        // that it's enabled for the starting version, so checking this for each version
        // incrementally is sufficient to ensure that it's enabled for the entire range.)
        val cdcDisabled = actions.exists {
          case m: Metadata => !isCDCEnabledOnTable(m)
          case _ => false
        }

        if (cdcDisabled) {
          throw DeltaCDFErrors.changeDataNotRecordedException(v, start, end)
        }

        // Set up buffers for all action types to avoid multiple passes.
        val cdcActions = ListBuffer[AddCDCFile]()
        val addActions = ListBuffer[AddFile]()
        val removeActions = ListBuffer[RemoveFile]()
        val ts = timestampsByVersion.get(v).orNull

        // Note that the CommitInfo is *not* guaranteed to be generated in 100% of cases.
        // We are using it only for a hotfix-safe mitigation/defense-in-depth - the value
        // extracted here cannot be relied on for correctness.
        var commitInfo: Option[CommitInfo] = None
        actions.foreach {
          case c: AddCDCFile =>
            cdcActions.append(c)
          case a: AddFile =>
            addActions.append(a)
          case r: RemoveFile =>
            removeActions.append(r)
          case i: CommitInfo => commitInfo = Some(i)
          case _ => // do nothing
        }

        // For UPDATE sql command, cdc actions provides the accurate commit type: update_postimage
        // and update_preimage.
        // For INSERT or DELETE, most of the times both cdc and add/remove actions will be
        // generated, either of them will result in the correct cdc data.
        // If there are CDC actions, we read them exclusively, and ignore the add/remove actions.
        if (cdcActions.nonEmpty) {
          changeFiles.append(CDCDataSpec(v, ts, cdcActions))
        } else {
          // MERGE will sometimes rewrite files in a way which *could* have changed data
          // (so dataChange = true) but did not actually do so (so no CDC will be produced).
          // In this case the correct CDC output is empty - we shouldn't serve it from
          // those files.
          // This should be handled within the command, but as a hotfix-safe fix, we check the
          // metrics. If the command reported 0 rows inserted, updated, or deleted, then CDC
          // shouldn't be produced.
          val isMerge = commitInfo.isDefined && commitInfo.get.operation == "MERGE"
          val knownToHaveNoChangedRows = {
            val metrics = commitInfo.flatMap(_.operationMetrics).getOrElse(Map.empty)
            // Note that if any metrics are missing, this condition will be false and we won't skip.
            // Unfortunately there are no predefined constants for these metric values.
            Seq("numTargetRowsInserted", "numTargetRowsUpdated", "numTargetRowsDeleted").forall {
              metrics.get(_).contains("0")
            }
          }
          if (isMerge && knownToHaveNoChangedRows) {
            // This was introduced for a hotfix, so we're mirroring the existing logic as closely
            // as possible - it'd likely be safe to just return an empty dataframe here.
            addFiles.append(CDCDataSpec(v, ts, Nil))
            removeFiles.append(CDCDataSpec(v, ts, Nil))
          } else {
            // Otherwise, we take the AddFile and RemoveFile actions with dataChange = true and
            // infer CDC from them.
            val addActions = actions.collect {
              case a: AddFile if a.dataChange => a
            }
            val removeActions = actions.collect {
              case r: RemoveFile if r.dataChange => r
            }
            addFiles.append(CDCDataSpec(v, ts, addActions))
            removeFiles.append(CDCDataSpec(v, ts, removeActions))
          }
        }
    }

    changeFiles, addFiles, removeFiles
  }

  case class CDCDataSpec[T <: FileAction](version: Long, timestamp: Timestamp, actions: Seq[T])

  /**
   * Determine if the metadata provided has cdc enabled or not.
   */
  def isCDCEnabledOnTable(metadata: Metadata): Boolean = {
    metadata.configuration.getOrElse("delta.enableChangeDataFeed", "false") == "true"
  }


  /**
   * DeltaHistoryManager.getCommits is not a public method, so we need to make local copies here
   * When calling getCommits, the initial few timestamp values may be wrong because they are not
   * properly monotonized. getCommitsSafe uses this to update the start value
   * far behind the first timestamp they care about to get correct values.
   */
  private val POTENTIALLY_UNMONOTONIZED_TIMESTAMPS = 100

  private[internal] def getCommitsSafe(
      logStore: ReadOnlyLogStore,
      logPath: Path,
      start: Long,
      end: Long): Array[Commit] = {
    val monotonizationStart =
      Seq(start - POTENTIALLY_UNMONOTONIZED_TIMESTAMPS, 0).max
    getCommits(logStore, logPath, monotonizationStart, end)
  }

  /**
   * Returns the commit version and timestamps of all commits in `[start, end)`. If `end` is not
   * specified, will return all commits that exist after `start`. Will guarantee that the commits
   * returned will have both monotonically increasing versions as well as timestamps.
   * Exposed for tests.
   */
  private[internal] def getCommits(
      logStore: ReadOnlyLogStore,
      logPath: Path,
      start: Long,
      end: Long): Array[Commit] = {
    val commits = logStore
      .listFrom(FileNames.deltaFile(logPath, start))
      .filter(f => FileNames.isDeltaFile(f.getPath))
      .map { fileStatus =>
        Commit(FileNames.deltaVersion(fileStatus.getPath), fileStatus.getModificationTime)
      }
      .takeWhile(_.version < end)

    monotonizeCommitTimestamps(commits.toArray)
  }

  /**
   * Makes sure that the commit timestamps are monotonically increasing with respect to commit
   * versions. Requires the input commits to be sorted by the commit version.
   */
  private[internal] def monotonizeCommitTimestamps[T <: CommitMarker](
      commits: Array[T]): Array[T] = {
    var i = 0
    val length = commits.length
    while (i < length - 1) {
      val prevTimestamp = commits(i).getTimestamp
      assert(commits(i).getVersion < commits(i + 1).getVersion, "Unordered commits provided.")
      if (prevTimestamp >= commits(i + 1).getTimestamp) {
        commits(i + 1) = commits(i + 1).withTimestamp(prevTimestamp + 1).asInstanceOf[T]
      }
      i += 1
    }
    commits
  }
}