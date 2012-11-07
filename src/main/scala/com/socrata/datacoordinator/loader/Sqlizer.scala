package com.socrata.datacoordinator.loader

import java.sql.ResultSet

trait Sqlizer {
  def logTransactionComplete() // whole-database log has : (dataset id, last updated at, new txn log serial id)
  def lockTableAgainstWrites(): String
}

/** Generates SQL for execution. */
trait DataSqlizer[CT, CV] extends Sqlizer {
  def datasetContext: DatasetContext[CT, CV]

  def insert(row: Row[CV]): String
  def update(row: Row[CV]): String
  def delete(id: CV): String

  // txn log has (serial, row id, who did the update)
  def logInsert(id: CV): String
  def logUpdate(id: CV): String
  def logDelete(id: CV): String

  def selectRow(id: CV): String

  def extract(resultSet: ResultSet, logicalColumn: String): CV

  def extractRow(resultSet: ResultSet): Row[CV] =
    datasetContext.fullSchema.keys.foldLeft(Map.empty[String, CV]) { (results, col) =>
      results + (col -> extract(resultSet, col))
    }
}

trait SchemaSqlizer[CT, CV] extends Sqlizer {
  // all these include log-generation statements in their output
  def addColumn(column: String, typ: CT): Iterator[String]
  def dropColumn(column: String, typ: CT): Iterator[String]
  def setPrimaryKeyColumn(column: String): Iterator[String]
  def copyTable(targetTable: String): Iterator[String] // this is also responsible for _creating_ the target table
}


