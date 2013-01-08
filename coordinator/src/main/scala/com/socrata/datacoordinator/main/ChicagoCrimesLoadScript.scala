package com.socrata.datacoordinator.main

import scala.collection.JavaConverters._

import java.sql.{Connection, DriverManager}
import java.util.concurrent.Executors

import org.joda.time.{DateTimeZone, DateTime}
import com.rojoma.simplearm.{SimpleArm, Managed}
import com.rojoma.simplearm.util._
import com.google.protobuf.{CodedOutputStream, CodedInputStream}

import com.socrata.soql.types._
import com.socrata.id.numeric.{IdProvider, FibonacciIdProvider, InMemoryBlockIdProvider}
import com.socrata.soql.environment.TypeName

import com.socrata.datacoordinator.main.soql.{SoQLRep, SoQLNullValue, SystemColumns}
import com.socrata.datacoordinator.truth.metadata._
import com.socrata.datacoordinator.truth.loader._
import com.socrata.datacoordinator.util.{CloseableIterator, RotateSchema, IdProviderPoolImpl, IdProviderPool}
import com.socrata.datacoordinator.manifest.TruthManifest
import com.socrata.datacoordinator.truth._
import com.socrata.datacoordinator.truth.metadata.sql.{PostgresGlobalLog, PostgresDatasetMapWriter, PostgresDatasetMapReader}
import com.socrata.datacoordinator.manifest.sql.SqlTruthManifest
import com.socrata.datacoordinator.truth.loader.sql._
import com.socrata.datacoordinator.truth.sql.{DatabasePopulator, SqlColumnRep}
import com.socrata.datacoordinator.id.RowId
import com.socrata.datacoordinator.{Row, MutableRow}
import com.socrata.datacoordinator.util.collection.ColumnIdMap
import com.socrata.datacoordinator.main.sql.{PostgresSqlLoaderProvider, AbstractSqlLoaderProvider}
import org.joda.time.format.{DateTimeFormat, DateTimeParser}
import java.io.Closeable

object ChicagoCrimesLoadScript extends App {
  val executor = Executors.newCachedThreadPool()


  def convertNum(x: String) =
    if(x.isEmpty) SoQLNullValue
    else BigDecimal(x)

  def convertBool(x: String) =
    if(x.isEmpty) SoQLNullValue
    else java.lang.Boolean.parseBoolean(x)

  val tsParser = DateTimeFormat.forPattern("MM/dd/yyyy hh:mm aa").withZoneUTC

  def convertTS(x: String) =
    if(x.isEmpty) SoQLNullValue
    else tsParser.parseDateTime(x)

  val fmt = """^\(([0-9.-]+), ([0-9.-]+)\)$""".r
  def convertLoc(x: String) =
    if(x.isEmpty) SoQLNullValue
    else {
      val mtch = fmt.findFirstMatchIn(x).get
      (mtch.group(1).toDouble, mtch.group(2).toDouble)
    }

  val converter: Map[SoQLType, String => Any] = Map (
    SoQLText -> identity[String],
    SoQLNumber -> convertNum,
    SoQLBoolean -> convertBool,
    SoQLFixedTimestamp -> convertTS,
    SoQLLocation -> convertLoc
  )

  try {
    val typeContext: TypeContext[SoQLType, Any] = new TypeContext[SoQLType, Any] {
      def isNull(value: Any): Boolean = SoQLNullValue == value

      def makeValueFromSystemId(id: RowId): Any = id

      def makeSystemIdFromValue(id: Any): RowId = id.asInstanceOf[RowId]

      def nullValue: Any = SoQLNullValue

      private val typesByStringName = SoQLType.typesByName.values.foldLeft(Map.empty[String, SoQLType]) { (acc, typ) =>
        acc + (typ.toString -> typ)
      }
      def typeFromName(name: String): SoQLType = typesByStringName(name)

      def nameFromType(typ: SoQLType): String = typ.toString

      def makeIdMap[T](idColumnType: SoQLType): RowUserIdMap[Any, T] =
        if(idColumnType == SoQLText) {
          new RowUserIdMap[Any, T] {
            val map = new java.util.HashMap[String, (String, T)]

            def put(x: Any, v: T) {
              val s = x.asInstanceOf[String]
              map.put(s.toLowerCase, (s, v))
            }

            def apply(x: Any): T = {
              val s = x.asInstanceOf[String]
              val k = s.toLowerCase
              if(map.containsKey(k)) map.get(k)._2
              else throw new NoSuchElementException
            }

            def get(x: Any): Option[T] = {
              val s = x.asInstanceOf[String]
              val k = s.toLowerCase
              if(map.containsKey(k)) Some(map.get(k)._2)
              else None
            }

            def clear() {
              map.clear()
            }

            def contains(x: Any): Boolean = {
              val s = x.asInstanceOf[String]
              map.containsKey(s.toLowerCase)
            }

            def isEmpty: Boolean = map.isEmpty

            def size: Int = map.size

            def foreach(f: (Any, T) => Unit) {
              val it = map.values.iterator
              while(it.hasNext) {
                val (k, v) = it.next()
                f(k, v)
              }
            }

            def valuesIterator: Iterator[T] =
              map.values.iterator.asScala.map(_._2)
          }
        } else {
          new SimpleRowUserIdMap[Any, T]
        }
    }

    val idSource = new InMemoryBlockIdProvider(releasable = true)

    val providerPool: IdProviderPool = new IdProviderPoolImpl(idSource, new FibonacciIdProvider(_))

    def rowCodecFactory(): RowLogCodec[Any] = new IdCachingRowLogCodec[Any] {
      def rowDataVersion: Short = 0

      // fixme; it'd be much better to do this in a manner simular to how column reps work

      protected def writeValue(target: CodedOutputStream, v: Any) {
        v match {
          case l: RowId =>
            target.writeRawByte(0)
            target.writeInt64NoTag(l.underlying)
          case s: String =>
            target.writeRawByte(1)
            target.writeStringNoTag(s)
          case bd: BigDecimal =>
            target.writeRawByte(2)
            target.writeStringNoTag(bd.toString)
          case b: Boolean =>
            target.writeRawByte(3)
            target.writeBoolNoTag(b)
          case ts: DateTime =>
            target.writeRawByte(4)
            target.writeStringNoTag(ts.getZone.getID)
            target.writeInt64NoTag(ts.getMillis)
          case tt: Tuple2[_,_] =>
            target.writeRawByte(5)
            target.writeDoubleNoTag(tt._1.asInstanceOf[Double])
            target.writeDoubleNoTag(tt._2.asInstanceOf[Double])
          case SoQLNullValue =>
            target.writeRawByte(-1)
        }
      }

      protected def readValue(source: CodedInputStream): Any =
        source.readRawByte() match {
          case 0 =>
            new RowId(source.readInt64())
          case 1 =>
            source.readString()
          case 2 =>
            BigDecimal(source.readString())
          case 3 =>
            source.readBool()
          case 4 =>
            val zone = DateTimeZone.forID(source.readString())
            new DateTime(source.readInt64(), zone)
          case 5 =>
            val lat = source.readDouble()
            val lon = source.readDouble()
            (lat, lon)
          case -1 =>
            SoQLNullValue
        }
    }

    trait RepFactory {
      def base: String
      def rep(columnBase: String): SqlColumnRep[SoQLType, Any]
    }

    def rep(typ: SoQLType) = new RepFactory {
      val base = typ.toString.take(3)
      def rep(columnBase: String) = SoQLRep.repFactories(typ)(columnBase)
    }

    val soqlRepFactory = SoQLRep.repFactories.keys.foldLeft(Map.empty[SoQLType, RepFactory]) { (acc, typ) =>
      acc + (typ -> rep(typ))
    }

    val mutator: DatabaseMutator[SoQLType, Any] = new DatabaseMutator[SoQLType, Any] {
      class PoNT(val conn: Connection) extends ProviderOfNecessaryThings {
        val now: DateTime = DateTime.now()
        val datasetMapReader: DatasetMapReader = new PostgresDatasetMapReader(conn)
        val datasetMapWriter: DatasetMapWriter = new PostgresDatasetMapWriter(conn)

        def datasetLog(ds: DatasetInfo): Logger[Any] = new SqlLogger[Any](
          conn,
          ds.logTableName,
          rowCodecFactory
        )

        val globalLog: GlobalLog = new PostgresGlobalLog(conn)
        val truthManifest: TruthManifest = new SqlTruthManifest(conn)
        val idProviderPool: IdProviderPool = providerPool

        def physicalColumnBaseForType(typ: SoQLType): String =
          soqlRepFactory(typ).base

        def genericRepFor(columnInfo: ColumnInfo): SqlColumnRep[SoQLType, Any] =
          soqlRepFactory(typeContext.typeFromName(columnInfo.typeName)).rep(columnInfo.physicalColumnBase)

        def schemaLoader(version: datasetMapWriter.VersionInfo, logger: Logger[Any]): SchemaLoader =
          new RepBasedSchemaLoader[SoQLType, Any](conn, logger) {
            def repFor(columnInfo: DatasetMapWriter#ColumnInfo): SqlColumnRep[SoQLType, Any] =
              genericRepFor(columnInfo)
          }

        def nameForType(typ: SoQLType): String = typeContext.nameFromType(typ)

        def rawDataLoader(table: VersionInfo, schema: ColumnIdMap[ColumnInfo], logger: Logger[Any]): Loader[Any] = {
          val lp = new AbstractSqlLoaderProvider(conn, providerPool, executor, typeContext) with PostgresSqlLoaderProvider[SoQLType, Any]
          lp(table, schema, rowPreparer(schema), logger, genericRepFor)
        }

        def dataLoader(table: VersionInfo, schema: ColumnIdMap[ColumnInfo], logger: Logger[Any]): Managed[Loader[Any]] =
          managed(rawDataLoader(table, schema, logger))

        def delogger(dataset: DatasetInfo) = new SqlDelogger[Any](conn, dataset.logTableName, rowCodecFactory)

        def rowPreparer(schema: ColumnIdMap[ColumnInfo]) =
          new RowPreparer[Any] {
            def findCol(name: String) =
              schema.values.iterator.find(_.logicalName == name).getOrElse(sys.error(s"No $name column?")).systemId
            val idColumn = findCol(SystemColumns.id)
            val createdAtColumn = findCol(SystemColumns.createdAt)
            val updatedAtColumn = findCol(SystemColumns.updatedAt)

            def prepareForInsert(row: Row[Any], sid: RowId): Row[Any] = {
              val tmp = new MutableRow[Any](row)
              tmp(idColumn) = sid
              tmp(createdAtColumn) = now
              tmp(updatedAtColumn) = now
              tmp.freeze()
            }

            def prepareForUpdate(row: Row[Any]): Row[Any] = {
              val tmp = new MutableRow[Any](row)
              tmp(updatedAtColumn) = now
              tmp.freeze()
            }
          }
      }

      def withTransaction[T]()(f: (ProviderOfNecessaryThings) => T): T = {
        using(DriverManager.getConnection("jdbc:postgresql://localhost:5432/robertm", "blist", "blist")) { conn =>
          conn.setAutoCommit(false)
          try {
            val result = f(new PoNT(conn))
            conn.commit()
            result
          } finally {
            conn.rollback()
          }
        }
      }

      def withSchemaUpdate[T](datasetId: String, user: String)(f: SchemaUpdate => T): T =
        withTransaction() { pont =>
          class Operations extends SchemaUpdate with Closeable {
            val now: DateTime = pont.now
            val datasetMapWriter: pont.datasetMapWriter.type = pont.datasetMapWriter
            val datasetInfo = datasetMapWriter.datasetInfo(datasetId).getOrElse(sys.error("no such dataset")) // TODO: Real error
            val tableInfo = datasetMapWriter.latest(datasetInfo)
            val datasetLog = pont.datasetLog(datasetInfo)

            val idProviderHandle = new LazyBox(pont.idProviderPool.borrow())
            lazy val idProvider: IdProvider = idProviderHandle.value

            val schemaLoader: SchemaLoader = pont.schemaLoader(tableInfo, datasetLog)

            def close() {
              if(idProviderHandle.initialized) pont.idProviderPool.release(idProviderHandle.value)
            }
          }

          using(new Operations) { operations =>
            val result = f(operations)
            operations.datasetLog.endTransaction() foreach { version =>
              pont.truthManifest.updateLatestVersion(operations.datasetInfo, version)
              pont.globalLog.log(operations.datasetInfo, version, pont.now, user)
            }
            result
          }
        }

      def withDataUpdate[T](datasetId: String, user: String)(f: DataUpdate => T): T =
        withTransaction() { pont =>
          class Operations extends DataUpdate with Closeable {
            val now: DateTime = pont.now
            val datasetMapWriter: pont.datasetMapWriter.type = pont.datasetMapWriter
            val datasetInfo = datasetMapWriter.datasetInfo(datasetId).getOrElse(sys.error("No such dataset?")) // TODO: better error
            val tableInfo = datasetMapWriter.latest(datasetInfo)
            val datasetLog = pont.datasetLog(datasetInfo)
            val idProviderHandle = new LazyBox(pont.idProviderPool.borrow())
            lazy val idProvider: IdProvider = idProviderHandle.value

            val schema = datasetMapWriter.schema(tableInfo)
            val dataLoader = pont.asInstanceOf[PoNT].rawDataLoader(tableInfo, schema, datasetLog)

            def close() {
              try {
                dataLoader.close()
              } finally {
                if(idProviderHandle.initialized) pont.idProviderPool.release(idProviderHandle.value)
              }
            }
          }

          using(new Operations) { operations =>
            val result = f(operations)
            operations.datasetLog.endTransaction() foreach { version =>
              pont.truthManifest.updateLatestVersion(operations.datasetInfo, version)
              pont.globalLog.log(operations.datasetInfo, version, pont.now, user)
            }
            result
          }
        }
    }

    val datasetCreator = new DatasetCreator(mutator, Map(
      SystemColumns.id -> SoQLID,
      SystemColumns.createdAt -> SoQLFixedTimestamp,
      SystemColumns.updatedAt -> SoQLFixedTimestamp
    ), SystemColumns.id)

    val columnAdder = new ColumnAdder(mutator)

    val primaryKeySetter = new PrimaryKeySetter(mutator)

    val upserter = new Upserter(mutator)

    // Everything above this point can be re-used for every operation

    using(DriverManager.getConnection("jdbc:postgresql://localhost:5432/robertm", "blist", "blist")) { conn =>
      conn.setAutoCommit(false)
      DatabasePopulator.populate(conn)
      conn.commit()
    }

    val user = "robertm"

    try { datasetCreator.createDataset("crimes", user) }
    catch { case _: DatasetAlreadyExistsException => /* pass */ }
    using(loadCSV("/home/robertm/chicagocrime.csv")) { it =>
      val types = Map(
        "ID" -> SoQLNumber,
        "Case Number" -> SoQLText,
        "Date" -> SoQLFixedTimestamp,
        "Block" -> SoQLText,
        "IUCR" -> SoQLText,
        "Primary Type" -> SoQLText,
        "Description" -> SoQLText,
        "Location Description" -> SoQLText,
        "Arrest" -> SoQLBoolean,
        "Domestic" -> SoQLBoolean,
        "Beat" -> SoQLText,
        "District" -> SoQLText,
        "Ward" -> SoQLText,
        "Community Area" -> SoQLText,
        "FBI Code" -> SoQLText,
        "X Coordinate" -> SoQLNumber,
        "Y Coordinate" -> SoQLNumber,
        "Year" -> SoQLText,
        "Updated On" -> SoQLFixedTimestamp,
        "Latitude" -> SoQLNumber,
        "Longitude" -> SoQLNumber,
        "Location" -> SoQLLocation
      )
      val headers = it.next()
      val schema = columnAdder.addToSchema("crimes", headers.map { x => x -> types(x) }.toMap, user).mapValues { ci =>
        (ci, typeContext.typeFromName(ci.typeName))
      }.toMap
      primaryKeySetter.makePrimaryKey("crimes", "ID", user)
      val start = System.nanoTime()
      upserter.upsert("crimes", user) { _ =>
        noopManagement(it.map(transformToRow(schema, headers, _)).map(Right(_)))
      }
      val end = System.nanoTime()
      println(s"Upsert took ${(end - start) / 1000000L}ms")
    }
    // columnAdder.addToSchema("crimes", Map("id" -> SoQLText, "penalty" -> SoQLText), user)
    // primaryKeySetter.makePrimaryKey("crimes", "id", user)
    // loadRows("crimes", upserter, user)
    // loadRows2("crimes", upserter, user)

//    mutator.withTransaction() { mutator =>
//      val t = mutator.datasetMapReader.datasetInfo("crimes").getOrElse(sys.error("No crimes db?"))
//      val delogger = mutator.delogger(t)
//
//      def pt(n: Long) = using(delogger.delog(n)) { it =>
//        it/*.filterNot(_.isInstanceOf[Delogger.RowDataUpdated[_]])*/.foreach { ev => println(n + " : " + ev) }
//      }
//
//      (1L to 6) foreach (pt)
//    }
  } finally {
    executor.shutdown()
  }

  def noopManagement[T](t: T): Managed[T] =
    new SimpleArm[T] {
      def flatMap[B](f: (T) => B): B = f(t)
    }

  def loadRows(ds: String, upserter: Upserter[SoQLType, Any], user: String) {
    upserter.upsert(ds, user) { schema =>
      val byName = RotateSchema(schema)
      noopManagement(Iterator[Either[Any, Row[Any]]](
        Right(Row(byName("id").systemId -> "robbery", byName("penalty").systemId -> "short jail term")),
        Right(Row(byName("id").systemId -> "murder", byName("penalty").systemId -> "long jail term"))
      ))
    }
  }

  def loadRows2(ds: String, upserter: Upserter[SoQLType, Any], user: String) {
    upserter.upsert(ds, user) { schema =>
      val byName = RotateSchema(schema)
      noopManagement(Iterator[Either[Any, Row[Any]]](
        Right(Row(byName("id").systemId -> "murder", byName("penalty").systemId -> "DEATH")),
        Left("robbery")
      ))
    }
  }

  def transformToRow(schema: Map[String, (ColumnInfo, SoQLType)], headers: IndexedSeq[String], row: IndexedSeq[String]): Row[Any] = {
    assert(headers.length == row.length, "Bad row; different number of columns from the headers")
    val result = new MutableRow[Any]
    (headers, row).zipped.foreach { (header, value) =>
      val (ci,typ) = schema(header)
      result += ci.systemId -> (try { converter(typ)(value) }
                                catch { case e: Exception => throw new Exception("Problem converting " + header + ": " + value, e) })
    }
    result.freeze()
  }

  def loadCSV(filename: String, skip: Int = 0): CloseableIterator[IndexedSeq[String]] = new CloseableIterator[IndexedSeq[String]] {
    import au.com.bytecode.opencsv._
    import java.io._
    val reader = new FileReader(filename)

    lazy val it = locally {
      val r = new CSVReader(reader)
      def loop(idx: Int = 1): Stream[Array[String]] = {
        r.readNext() match {
          case null => Stream.empty
          case row => row #:: loop(idx + 1)
        }
      }
      loop().iterator
    }

    def hasNext = it.hasNext
    def next() = it.next()

    def close() {
      reader.close()
    }
  }
}
