package com.socrata.datacoordinator.main

import org.joda.time.DateTime
import com.rojoma.simplearm.Managed

import com.socrata.datacoordinator.truth.metadata._
import com.socrata.datacoordinator.truth.loader._
import com.socrata.datacoordinator.manifest.TruthManifest
import com.socrata.datacoordinator.util.IdProviderPool
import com.socrata.datacoordinator.util.collection.ColumnIdMap
import com.socrata.id.numeric.IdProvider

abstract class DatabaseMutator[CT, CV] {
  trait ProviderOfNecessaryThings {
    val now: DateTime
    val datasetMapReader: DatasetMapReader
    val datasetMapWriter: DatasetMapWriter
    def datasetLog(ds: DatasetInfo): Logger[CV]
    def delogger(ds: DatasetInfo): Delogger[CV]
    val globalLog: GlobalLog
    val truthManifest: TruthManifest
    val idProviderPool: IdProviderPool
    def physicalColumnBaseForType(typ: CT): String
    def schemaLoader(version: datasetMapWriter.VersionInfo, logger: Logger[CV]): SchemaLoader
    def nameForType(typ: CT): String

    def dataLoader(table: VersionInfo, schema: ColumnIdMap[ColumnInfo], logger: Logger[CV]): Managed[Loader[CV]]
    def rowPreparer(schema: ColumnIdMap[ColumnInfo]): RowPreparer[CV]

    def singleId() = {
      val provider = idProviderPool.borrow()
      try {
        provider.allocate()
      } finally {
        idProviderPool.release(provider)
      }
    }
  }

  trait BaseUpdate {
    val now: DateTime
    val datasetMapWriter: DatasetMapWriter
    val datasetInfo: datasetMapWriter.DatasetInfo
    val tableInfo: datasetMapWriter.VersionInfo
    val datasetLog: Logger[CV]
    val idProvider: IdProvider
  }

  trait SchemaUpdate extends BaseUpdate {
    val schemaLoader: SchemaLoader
  }

  trait DataUpdate extends BaseUpdate {
    val schema: ColumnIdMap[ColumnInfo]
    val dataLoader: Loader[CV]
  }

  def withTransaction[T]()(f: ProviderOfNecessaryThings => T): T
  def withSchemaUpdate[T](datasetId: String, user: String)(f: SchemaUpdate => T): T
  def withDataUpdate[T](datasetId: String, user: String)(f: DataUpdate => T): T
}
