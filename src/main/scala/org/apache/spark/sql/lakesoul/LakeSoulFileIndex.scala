/*
 * Copyright [2022] [DMetaSoul Team]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.lakesoul

import java.net.URI

import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.spark.sql.catalyst.expressions.{Cast, Expression, GenericInternalRow, Literal}
import org.apache.spark.sql.execution.datasources.{PartitionDirectory, PartitionSpec, PartitioningAwareFileIndex}
import org.apache.spark.sql.lakesoul.LakeSoulFileIndexUtils._
import org.apache.spark.sql.lakesoul.utils.DataFileInfo
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{AnalysisException, SparkSession}

import scala.collection.mutable

/** file index for data source v2 */
abstract class LakeSoulFileIndexV2(val spark: SparkSession,
                                   val snapshotManagement: SnapshotManagement)
  extends PartitioningAwareFileIndex(spark, Map.empty[String, String], None) {

  lazy val tableName: String = snapshotManagement.table_name

  def getFileInfo(filters: Seq[Expression]): Seq[DataFileInfo] = matchingFiles(filters)


  override def rootPaths: Seq[Path] = snapshotManagement.snapshot.getTableInfo.table_path :: Nil

  override def refresh(): Unit = {}

  /**
    * Returns all matching/valid files by the given `partitionFilters` and `dataFilters`
    */
  def matchingFiles(partitionFilters: Seq[Expression],
                    dataFilters: Seq[Expression] = Nil): Seq[DataFileInfo]


  override def partitionSchema: StructType = snapshotManagement.snapshot.getTableInfo.range_partition_schema

  override def listFiles(partitionFilters: Seq[Expression],
                         dataFilters: Seq[Expression]): Seq[PartitionDirectory] = {
    val timeZone = spark.sessionState.conf.sessionLocalTimeZone

    matchingFiles(partitionFilters, dataFilters)
      .groupBy(_.range_partitions).map {
      case (partitionValues, files) =>
        val rowValues: Array[Any] = partitionSchema.map { p =>
          Cast(Literal(partitionValues(p.name)), p.dataType, Option(timeZone)).eval()
        }.toArray

        //file status
        val fileStats = files.map { f =>
          new FileStatus(
            /* length */ f.size,
            /* isDir */ false,
            /* blockReplication */ 0,
            /* blockSize */ 1,
            /* modificationTime */ f.modification_time,
            absolutePath(f.file_path, tableName))
        }.toArray

        PartitionDirectory(new GenericInternalRow(rowValues), fileStats)
    }.toSeq
  }


  override def partitionSpec(): PartitionSpec = {
    throw new AnalysisException(
      s"Function partitionSpec() is not support in merge.")
  }


  override def leafFiles: mutable.LinkedHashMap[Path, FileStatus] = {
    throw new AnalysisException(
      s"Function leafFiles() is not support in merge.")
  }

  override def leafDirToChildrenFiles: Map[Path, Array[FileStatus]] = {
    throw new AnalysisException(
      s"Function leafDirToChildrenFiles() is not support in merge.")
  }

}


case class DataSoulFileIndexV2(override val spark: SparkSession,
                               override val snapshotManagement: SnapshotManagement,
                               partitionFilters: Seq[Expression] = Nil)
  extends LakeSoulFileIndexV2(spark, snapshotManagement) {

  override def matchingFiles(partitionFilters: Seq[Expression],
                             dataFilters: Seq[Expression]): Seq[DataFileInfo] = {
    PartitionFilter.filesForScan(
      snapshotManagement.snapshot,
      this.partitionFilters ++ partitionFilters ++ dataFilters)
  }

  override def inputFiles: Array[String] = {
    PartitionFilter.filesForScan(snapshotManagement.snapshot, partitionFilters)
      .map(f => absolutePath(f.file_path, tableName).toString)
  }

  override def sizeInBytes: Long = snapshotManagement.snapshot.sizeInBytes(partitionFilters)
}


/**
  * A [[LakeSoulFileIndexV2]] that generates the list of files from a given list of files
  * that are within a version range of SnapshotManagement.
  */
case class BatchDataSoulFileIndexV2(override val spark: SparkSession,
                                    override val snapshotManagement: SnapshotManagement,
                                    files: Seq[DataFileInfo])
  extends LakeSoulFileIndexV2(spark, snapshotManagement) {


  override def matchingFiles(partitionFilters: Seq[Expression],
                             dataFilters: Seq[Expression]): Seq[DataFileInfo] = {
    import spark.implicits._
    PartitionFilter.filterFileList(
      snapshotManagement.snapshot.getTableInfo.range_partition_schema,
      files.toDF(),
      partitionFilters)
      .as[DataFileInfo]
      .collect()
  }


  override def inputFiles: Array[String] = {
    files.map(file => absolutePath(file.file_path, tableName).toString).toArray
  }


  override val sizeInBytes: Long = files.map(_.size).sum

}


object LakeSoulFileIndexUtils {
  def absolutePath(child: String, tableName: String): Path = {
    val p = new Path(new URI(child))
    if (p.isAbsolute) {
      p
    } else {
      new Path(tableName, p)
    }
  }


}

