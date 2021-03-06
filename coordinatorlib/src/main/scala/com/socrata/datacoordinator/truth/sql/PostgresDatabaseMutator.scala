package com.socrata.datacoordinator.truth
package sql

import java.sql.Connection
import javax.sql.DataSource

import org.joda.time.DateTime
import com.rojoma.simplearm.util._
import com.rojoma.simplearm.Managed

import com.socrata.datacoordinator.truth.metadata._
import com.socrata.datacoordinator.truth.metadata.sql.{PostgresGlobalLog, PostgresDatasetMapWriter}
import com.socrata.datacoordinator.truth.loader.{DatasetContentsCopier, Logger, SchemaLoader, Loader, Report, RowPreparer}
import com.socrata.datacoordinator.truth.loader.sql.{RepBasedSqlDatasetContentsCopier, SqlLogger}
import com.socrata.datacoordinator.truth.{TypeContext, RowLogCodec}
import com.socrata.datacoordinator.util.collection.ColumnIdMap
import com.socrata.datacoordinator.id.{DatasetId, RowId}
import com.rojoma.simplearm.SimpleArm
import scala.concurrent.duration.Duration
import com.socrata.datacoordinator.util.{RowDataProvider, TimingReport}
import com.socrata.datacoordinator.truth.universe._
import com.socrata.datacoordinator.truth.metadata.ColumnInfo
import com.socrata.datacoordinator.truth.metadata.DatasetInfo
import com.socrata.datacoordinator.truth.metadata.CopyInfo

// Does this need to be *Postgres*, or is all postgres-specific stuff encapsulated in its paramters?
// Actually does this need to be in the sql package at all now that Universe exists?
class PostgresDatabaseMutator[CT, CV](universe: Managed[Universe[CT, CV] with LoggerProvider with SchemaLoaderProvider with LoaderProvider with TruncatorProvider with DatasetContentsCopierProvider with DatasetMapWriterProvider with GlobalLogProvider])
  extends LowLevelDatabaseMutator[CT, CV]
{
  // type LoaderProvider = (CopyInfo, ColumnIdMap[ColumnInfo], RowPreparer[CV], IdProvider, Logger[CV], ColumnInfo => SqlColumnRep[CT, CV]) => Loader[CV]

  private class S(val universe: Universe[CT, CV] with LoggerProvider with SchemaLoaderProvider with LoaderProvider with TruncatorProvider with DatasetContentsCopierProvider with DatasetMapWriterProvider with GlobalLogProvider) extends MutationContext {
    lazy val now = universe.transactionStart

    final def loadLatestVersionOfDataset(datasetId: DatasetId, lockTimeout: Duration): Option[DatasetCopyContext[CT]] = {
      val map = datasetMap
      map.datasetInfo(datasetId, lockTimeout) map { datasetInfo =>
        val latest = map.latest(datasetInfo)
        val schema = map.schema(latest)
        new DatasetCopyContext(latest, schema)
      }
    }

    def logger(datasetInfo: DatasetInfo): Logger[CT, CV] =
      universe.logger(datasetInfo)

    def schemaLoader(logger: Logger[CT, CV]): SchemaLoader[CT] =
      universe.schemaLoader(logger)

    def truncate(table: CopyInfo, logger: Logger[CT, CV]) =
      universe.truncator.truncate(table, logger)

    def datasetContentsCopier(logger: Logger[CT, CV]): DatasetContentsCopier[CT] =
      universe.datasetContentsCopier(logger)

    def globalLog = universe.globalLog

    def finishDatasetTransaction(username: String, copyInfo: CopyInfo) {
      logger(copyInfo.datasetInfo).endTransaction() foreach { ver =>
        datasetMap.updateDataVersion(copyInfo, ver)
        globalLog.log(copyInfo.datasetInfo, ver, now, username)
      }
    }

    def datasetMap = universe.datasetMapWriter

    def withDataLoader[A](copyCtx: DatasetCopyContext[CT], logger: Logger[CT, CV], replaceUpdatedRows: Boolean)(f: (Loader[CV]) => A): (Report[CV], Long, A) = {
      val idProvider = new RowDataProvider(copyCtx.datasetInfo.nextCounterValue)
      for(loader <- universe.loader(copyCtx, idProvider, logger, replaceUpdatedRows)) yield {
        val result = f(loader)
        val report = loader.report
        (report, idProvider.finish(), result)
      }
    }
  }

  def openDatabase: Managed[MutationContext] = new SimpleArm[MutationContext] {
    def flatMap[A](f: MutationContext => A): A =
      for { u <- universe } yield f(new S(u))
  }
}
