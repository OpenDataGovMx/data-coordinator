package com.socrata.datacoordinator.loader

import java.sql.{PreparedStatement, ResultSet}

class TestDataSqlizer(user: String, val datasetContext: DatasetContext[TestColumnType, TestColumnValue]) extends TestSqlizer with DataSqlizer[TestColumnType, TestColumnValue] {
  val dataTableName = datasetContext.baseName + "_data"
  val logTableName = datasetContext.baseName + "_log"

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

  val prepareUserIdInsertStatement =
    "INSERT INTO " + dataTableName + " (" + columns.mkString(",") + ") SELECT " + columns.map(_ => "?").mkString(",") + " WHERE NOT EXISTS (SELECT 1 FROM " + dataTableName + " WHERE " + pkCol + " = ?)"

  def prepareSystemIdInsertStatement = prepareUserIdInsertStatement

  val prepareUserIdDeleteStatement =
    "DELETE FROM " + dataTableName + " WHERE " + pkCol + " = ?"

  def prepareSystemIdDeleteStatement = prepareUserIdDeleteStatement

  def prepareSystemIdDelete(stmt: PreparedStatement, id: Long) {
    stmt.setLong(1, id)
  }

  def prepareUserIdDelete(stmt: PreparedStatement, id: TestColumnValue) {
    datasetContext.userPrimaryKeyColumn match {
      case Some(c) =>
        add(stmt, 1, c, id)
      case None =>
        add(stmt, 1, datasetContext.systemIdColumnName, id)
    }
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

  def prepareSystemIdInsert(stmt: PreparedStatement, sid: Long, row: Row[TestColumnValue]) {
    val trueRow = row + (datasetContext.systemIdColumnName -> LongValue(sid))
    var i = 1

    for(k <- keys) {
      add(stmt, i, k, trueRow.getOrElse(k, NullValue))
      i += 1
    }
    stmt.setLong(i, sid)
  }

  def prepareUserIdInsert(stmt: PreparedStatement, sid: Long, row: Row[TestColumnValue]) {
    val trueRow = row + (datasetContext.systemIdColumnName -> LongValue(sid))
    var i = 1

    for(k <- keys) {
      add(stmt, i, k, trueRow.getOrElse(k, NullValue))
      i += 1
    }
    datasetContext.userPrimaryKeyColumn match {
      case Some(c) =>
        add(stmt, i, c, trueRow.getOrElse(c, NullValue))
      case None =>
        stmt.setLong(i, sid)
    }
  }

  def sqlizeSystemIdUpdate(sid: Long, row: Row[TestColumnValue]) =
    sqlizeUserIdUpdate(row)

  def sqlizeUserIdUpdate(row: Row[TestColumnValue]) =
    "UPDATE " + dataTableName + " SET " + (row - pkCol).map { case (col, v) => mapToPhysical(col) + " = " + v.sqlize }.mkString(",") + " WHERE " + pkCol + " = " + row(datasetContext.primaryKeyColumn).sqlize

  val findCurrentVersion =
    "SELECT COALESCE(MAX(id), 0) FROM " + logTableName

  val prepareLogRowsChanged =
    "INSERT INTO " + logTableName + " (id, rows, who) VALUES (?,?," + userSqlized + ")"

  val logRowsSize = 65000

  def selectRow(id: TestColumnValue): String =
    "SELECT id," + columns.mkString(",") + " FROM " + dataTableName + " WHERE " + pkCol + " = " + id.sqlize

  def extract(resultSet: ResultSet, logicalColumn: String) = {
    datasetContext.fullSchema(logicalColumn) match {
      case LongColumn =>
        val l = resultSet.getLong(mapToPhysical(logicalColumn))
        if(resultSet.wasNull) NullValue
        else LongValue(l)
      case StringColumn =>
        val s = resultSet.getString(mapToPhysical(logicalColumn))
        if(s == null) NullValue
        else StringValue(s)
    }
  }

  // TODO: it is possible that grouping this differently will be more performant in Postgres
  // (e.g., having too many items in an IN clause might cause a full-table scan) -- we need
  // to test this and if necessary find a good heuristic.
  def findSystemIds(ids: Iterator[TestColumnValue]): Iterator[String] = {
    require(datasetContext.hasUserPrimaryKey, "findSystemIds called without a user primary key")
    if(ids.isEmpty) {
      Iterator.empty
    } else {
      val sql = ids.map(_.sqlize).mkString("SELECT id AS sid, " + pkCol + " AS uid FROM " + dataTableName + " WHERE " + pkCol + " IN (", ",", ")")
      Iterator.single(sql)
    }
  }

  def extractIdPairs(rs: ResultSet) = {
    val typ = datasetContext.userSchema(datasetContext.userPrimaryKeyColumn.getOrElse(sys.error("extractIdPairs called without a user primary key")))
    def loop(): Stream[IdPair[TestColumnValue]] = {
      if(rs.next()) {
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
        IdPair(sid, uid) #:: loop()
      } else {
        Stream.empty
      }
    }
    loop().iterator
  }
}
