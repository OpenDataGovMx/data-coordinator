package com.socrata.datacoordinator
package truth.loader
package sql

import scala.collection.mutable

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.io.Closeable

import com.rojoma.json.ast._
import com.rojoma.json.util.JsonUtil
import com.rojoma.json.codec.JsonCodec
import com.rojoma.simplearm.util._

import com.socrata.datacoordinator.util.CloseableIterator
import com.socrata.datacoordinator.truth.DatasetContext

class TestDataSqlizer(tableBase: String, user: String, val datasetContext: DatasetContext[TestColumnType, TestColumnValue]) extends DataSqlizer[TestColumnType, TestColumnValue] {
  val dataTableName = tableBase + "_data"
  val logTableName = tableBase + "_log"

  def typeContext = TestTypeContext

  def sizeof(x: Long) = 8
  def sizeof(s: String) = s.length << 1
  def sizeofNull = 1

  def softMaxBatchSize = 20000000

  def sizerFrom(base: Int, t: TestColumnType): TestColumnValue => Int = t match {
    case LongColumn => {
      case LongValue(v) => base+8
      case NullValue => base+4
      case StringValue(_) => sys.error("Expected long, got string")
    }
    case StringColumn => {
      case StringValue(v) => base+v.length
      case NullValue => base+4
      case LongValue(_) => sys.error("Expected string, got long")
    }
  }

  def updateSizerForType(c: String, t: TestColumnType) = sizerFrom(c.length, t)
  def insertSizerForType(c: String, t: TestColumnType) = sizerFrom(0, t)

  val updateSizes = datasetContext.fullSchema.map { case (c, t) =>
    c -> updateSizerForType(c, t)
  }

  val insertSizes = datasetContext.fullSchema.map { case (c, t) =>
    c -> insertSizerForType(c, t)
  }

  def sizeofDelete = sizeof(0L)

  val baseUpdateSize = 50
  def sizeofUpdate(row: Row[TestColumnValue]) =
    row.foldLeft(baseUpdateSize) { (total, cv) =>
      val (c,v) = cv
      total + updateSizes(c)(v)
    }

  def sizeofInsert(row: Row[TestColumnValue]) =
    row.foldLeft(0) { (total, cv) =>
      val (c,v) = cv
      total + insertSizes(c)(v)
    }

  def mapToPhysical(column: String): String =
    if(datasetContext.systemSchema.contains(column)) {
      column.substring(1)
    } else if(datasetContext.userSchema.contains(column)) {
      "u_" + column
    } else {
      sys.error("unknown column " + column)
    }

  val keys = datasetContext.fullSchema.keys.toSeq
  val columns = keys.map(mapToPhysical)
  val pkCol = mapToPhysical(datasetContext.primaryKeyColumn)

  val userSqlized = StringValue(user).sqlize

  val insertPrefix = "INSERT INTO " + dataTableName + " (" + columns.mkString(",") + ") SELECT "
  val insertMidfix = " WHERE NOT EXISTS (SELECT 1 FROM " + dataTableName + " WHERE " + pkCol + " = "
  val insertSuffix = ")"

  val bulkInsertStatement =
    "COPY " + dataTableName + " (" + columns.mkString(",") + ") from stdin with csv"

  def insertBatch(conn: Connection)(f: Inserter => Unit): Long = {
    using(new InserterImpl(conn)) { inserter =>
      f(inserter)
      inserter.stmt.executeBatch().foldLeft(0L)(_+_)
    }
  }

  class InserterImpl(conn: Connection) extends Inserter with Closeable {
    val stmt = conn.prepareStatement("INSERT INTO " + dataTableName + "(" + columns.mkString(",") + ") SELECT " + columns.map(_ => "?").mkString(",") + " WHERE NOT EXISTS (SELECT 1 FROM " + dataTableName + " WHERE " + pkCol + " = ?)")

    def insert(sid: Long, row: Row[TestColumnValue]) {
      val trueRow = row + (datasetContext.systemIdColumnName -> LongValue(sid))
      var i = 1
      val it = keys.iterator
      while(it.hasNext) {
        val k = it.next()
        add(stmt, i, k, trueRow.getOrElse(k, NullValue))
        i += 1
      }
      add(stmt, i, datasetContext.primaryKeyColumn, trueRow(datasetContext.primaryKeyColumn))
      stmt.addBatch()
    }

    def close() {
      stmt.close()
    }
  }

  def csvize(sb: java.lang.StringBuilder, k: String, v: TestColumnValue) = {
    v match {
      case StringValue(s) =>
        sb.append('"')
        sb.append(s.replaceAllLiterally("\"", "\"\""))
        sb.append('"')
      case LongValue(n) =>
        sb.append(n)
      case NullValue =>
        // nothing
    }
  }


  def prepareSystemIdInsert(stmt: PreparedStatement, sid: Long, row: Row[TestColumnValue]) {
    val trueRow = row + (datasetContext.systemIdColumnName -> LongValue(sid))
    var i = 1

    for(k <- keys) {
      add(stmt, i, k, trueRow.getOrElse(k, NullValue))
      i += 1
    }

    stmt.setLong(i, sid)
  }

  def prepareUserIdInsert(stmt: PreparedStatement, sid: Long, row: Row[TestColumnValue]) = {
    val trueRow = row + (datasetContext.systemIdColumnName -> LongValue(sid))
    var i = 1

    for(k <- keys) {
      add(stmt, i, k, trueRow.getOrElse(k, NullValue))
      i += 1
    }

    val c = datasetContext.userPrimaryKeyColumn.getOrElse(sys.error("No user PK column defined"))
    add(stmt, i, c, trueRow.getOrElse(c, NullValue))
  }


  val prepareUserIdDeleteStatement =
    "DELETE FROM " + dataTableName + " WHERE " + pkCol + " = ?"

  def prepareSystemIdDeleteStatement = prepareUserIdDeleteStatement

  def prepareSystemIdDelete(stmt: PreparedStatement, id: Long) {
    stmt.setLong(1, id)
  }

  def prepareUserIdDelete(stmt: PreparedStatement, id: TestColumnValue) {
    val c = datasetContext.userPrimaryKeyColumn.getOrElse(sys.error("no user id column defined"))
    add(stmt, 1, c, id)
  }

  def add(stmt: PreparedStatement, i: Int, k: String, v: TestColumnValue) {
    datasetContext.fullSchema(k) match {
      case StringColumn =>
        v match {
          case StringValue(s) => stmt.setString(i, s)
          case NullValue => stmt.setNull(i, java.sql.Types.VARCHAR)
          case LongValue(_) => sys.error("Tried to store a long in a text column?")
        }
      case LongColumn =>
        v match {
          case LongValue(l) => stmt.setLong(i, l)
          case NullValue => stmt.setNull(i, java.sql.Types.NUMERIC)
          case StringValue(s) => sys.error("Tried to store a text in a long column?")
        }
    }
  }

  def sqlizeSystemIdUpdate(sid: Long, row: Row[TestColumnValue]) =
    sqlizeUserIdUpdate(row)

  def sqlizeUserIdUpdate(row: Row[TestColumnValue]) =
    "UPDATE " + dataTableName + " SET " + (row - pkCol).map { case (col, v) => mapToPhysical(col) + " = " + v.sqlize }.mkString(",") + " WHERE " + pkCol + " = " + row(datasetContext.primaryKeyColumn).sqlize

  val findCurrentVersion =
    "SELECT COALESCE(MAX(version), 0) FROM " + logTableName

  def newRowAuxDataAccumulator(auxUser: (LogAuxColumn) => Unit) = new RowAuxDataAccumulator {
    var sw: java.io.StringWriter = _
    var didOne: Boolean = _

    reset()

    def reset() {
      sw = new java.io.StringWriter
      sw.write('[')
      didOne = false
    }

    def maybeComma() {
      if(didOne) sw.write(',')
      else didOne = true
    }

    def maybeFlush() {
      if(sw.getBuffer.length > logRowsSize) flush()
    }

    val logRowsSize = 65000

    def flush() {
      sw.write(']')
      val str = sw.toString
      reset()
      if(str != "[]") {
        auxUser(str)
      }
    }

    implicit val jCodec = new JsonCodec[TestColumnValue] {
      def encode(x: TestColumnValue) = x match {
        case StringValue(s) => JString(s)
        case LongValue(n) => JNumber(n)
        case NullValue => JNull
      }

      def decode(x: JValue) = x match {
        case JString(s) => Some(StringValue(s))
        case JNumber(n) => Some(LongValue(n.toLong))
        case JNull => Some(NullValue)
        case _ => None
      }
    }

    val codec = implicitly[JsonCodec[Map[String, Map[String, TestColumnValue]]]]

    def insert(systemID: Long, row: Row[TestColumnValue]) {
      maybeComma()
      // sorting because this is test code and we want to be able to check it sanely
      JsonUtil.writeJson(sw, Map("i" -> (scala.collection.immutable.SortedMap(datasetContext.systemIdColumnName -> LongValue(systemID)) ++ row)))
      maybeFlush()
    }

    def update(systemID: Long, row: Row[TestColumnValue]) {
      maybeComma()
      // sorting because this is test code and we want to be able to check it sanely
      JsonUtil.writeJson(sw, Map("u" -> (scala.collection.immutable.SortedMap(datasetContext.systemIdColumnName -> LongValue(systemID)) ++ row)))
      maybeFlush()
    }

    def delete(systemID: Long) {
      maybeComma()
      sw.write(systemID.toString)
      maybeFlush()
    }

    def finish() {
      flush()
    }
  }

  type LogAuxColumn = String

  val prepareLogRowsChangedStatement =
    "INSERT INTO " + logTableName + " (version, subversion, rows, who) VALUES (?,?,?," + userSqlized + ")"

  def prepareLogRowsChanged(stmt: PreparedStatement, version: Long, subversion: Long, rowsJson: LogAuxColumn): Int = {
    stmt.setLong(1, version)
    stmt.setLong(2, subversion)
    stmt.setString(3, rowsJson)
    sizeof(version) + sizeof(subversion) + sizeof(rowsJson)
  }

  def selectRow(id: TestColumnValue): String =
    "SELECT id," + columns.mkString(",") + " FROM " + dataTableName + " WHERE " + pkCol + " = " + id.sqlize

  def extractRow(resultSet: ResultSet) = {
    val result = Map.newBuilder[String, TestColumnValue]
    for(logicalColumn <- datasetContext.fullSchema.keys) {
      val v = datasetContext.fullSchema(logicalColumn) match {
        case LongColumn =>
          val l = resultSet.getLong(mapToPhysical(logicalColumn))
          if(resultSet.wasNull) NullValue
          else LongValue(l)
        case StringColumn =>
          val s = resultSet.getString(mapToPhysical(logicalColumn))
          if(s == null) NullValue
          else StringValue(s)
      }
      result += logicalColumn -> v
    }
    result.result()
  }

  // TODO: it is possible that grouping this differently will be more performant in Postgres
  // (e.g., having too many items in an IN clause might cause a full-table scan) -- we need
  // to test this and if necessary find a good heuristic.
  def findSystemIds(conn: Connection, ids: Iterator[TestColumnValue]): CloseableIterator[Seq[IdPair[TestColumnValue]]] = {
    val typ = datasetContext.userSchema(datasetContext.userPrimaryKeyColumn.getOrElse(sys.error("findSystemIds called without a user primary key")))
    if(ids.isEmpty) {
      CloseableIterator.empty
    } else {
      for {
        stmt <- managed(conn.createStatement())
        rs <- managed(stmt.executeQuery(ids.map(_.sqlize).mkString("SELECT id AS sid, " + pkCol + " AS uid FROM " + dataTableName + " WHERE " + pkCol + " IN (", ",", ")")))
      } yield {
        val buf = new mutable.ArrayBuffer[IdPair[TestColumnValue]]
        while(rs.next()) {
          val sid = rs.getLong("sid")
          val uid = typ match {
            case LongColumn =>
              val l = rs.getLong("uid")
              if(rs.wasNull) NullValue
              else LongValue(l)
            case StringColumn =>
              val s = rs.getString("uid")
              if(s == null) NullValue
              else StringValue(s)
          }
          buf += IdPair(sid, uid)
        }
        CloseableIterator.single(buf)
      }
    }
  }
}