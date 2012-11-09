package com.socrata.datacoordinator.loader

import scala.collection.JavaConverters._

import java.sql.Connection

import com.rojoma.simplearm.util._

import com.socrata.id.numeric.{Unallocatable, IdProvider}

class StupidPostgresTransaction[CT, CV](val connection: Connection,
                                        val typeContext: TypeContext[CV],
                                        val sqlizer: DataSqlizer[CT, CV],
                                        val idProvider: IdProvider with Unallocatable)
  extends Transaction[CV]
{
  val datasetContext = sqlizer.datasetContext

  var totalRows = 0
  val inserted = new java.util.HashMap[Int, CV]
  val elided = new java.util.HashMap[Int, (CV, Int)]
  val updated = new java.util.HashMap[Int, CV]
  val deleted = new java.util.HashMap[Int, CV]
  val errors = new java.util.HashMap[Int, Failure[CV]]

  def nextJobNum() = {
    val r = totalRows
    totalRows += 1
    r
  }

  def upsert(row: Row[CV]) {
    val job = nextJobNum()
    datasetContext.userPrimaryKeyColumn match {
      case Some(pkCol) =>
        datasetContext.userPrimaryKey(row) match {
          case Some(id) =>
            val updatedCount = using(connection.createStatement()) { stmt =>
              stmt.executeUpdate(sqlizer.sqlizeUserIdUpdate(row))
            }
            if(updatedCount == 0) {
              val sid = idProvider.allocate()
              using(connection.prepareStatement(sqlizer.prepareUserIdInsertStatement)) { stmt =>
                sqlizer.prepareUserIdInsert(stmt, sid, row)
                val results = stmt.executeBatch().toSeq
                if(results != Seq(1)) {
                  sys.error("From insert: " + results)
                }
              }
              logChanged(sid)
              inserted.put(job, id)
            } else {
              val sid = findSid(id).get
              logChanged(sid)
              updated.put(job, id)
            }
          case None =>
            errors.put(job, NoPrimaryKey)
        }
      case None =>
        datasetContext.systemId(row) match {
          case Some(id) =>
            using(connection.createStatement()) { stmt =>
              if(stmt.executeUpdate(sqlizer.sqlizeSystemIdUpdate(id, row)) == 1) {
                updated.put(job, typeContext.makeValueFromSystemId(id))
                logChanged(id)
              } else
                errors.put(job, NoSuchRowToUpdate(typeContext.makeValueFromSystemId(id)))
            }
          case None =>
            val sid = idProvider.allocate()
            using(connection.prepareStatement(sqlizer.prepareSystemIdInsertStatement)) { stmt =>
              sqlizer.prepareSystemIdInsert(stmt, sid, row)
              val results = stmt.executeBatch().toSeq
              if(results != Seq(1)) {
                sys.error("From insert: " + results)
              }
            }
            logChanged(sid)
            inserted.put(job, typeContext.makeValueFromSystemId(sid))
        }
    }
  }

  def logChanged(sid: Long) {
    using(connection.prepareStatement(sqlizer.prepareLogRowChanged)) { stmt =>
      stmt.setLong(1, sid)
      stmt.executeUpdate()
    }
  }

  def findSid(id: CV): Option[Long] = {
    using(connection.createStatement()) { stmt =>
      val sql = sqlizer.findSystemIds(Iterator.single(id)).toSeq
      assert(sql.length == 1)
      using(stmt.executeQuery(sql.head)) { rs =>
        val sids = sqlizer.extractIdPairs(rs).map(_.systemId).toSeq
        assert(sids.length < 2)
        sids.headOption
      }
    }
  }

  def delete(id: CV) {
    val job = nextJobNum()
    datasetContext.userPrimaryKeyColumn match {
      case Some(pkCol) =>
        using(connection.prepareStatement(sqlizer.prepareUserIdDeleteStatement)) { stmt =>
          val sid = findSid(id)
          sqlizer.prepareUserIdDelete(stmt, id)
          val results = stmt.executeBatch().toSeq
          if(results != Seq(1)) {
            assert(!sid.isDefined)
            errors.put(job, NoSuchRowToDelete(id))
          } else {
            assert(sid.isDefined)
            deleted.put(job, id)
            logChanged(sid.get)
          }
        }
      case None =>
        val sid = typeContext.makeSystemIdFromValue(id)
        using(connection.prepareStatement(sqlizer.prepareSystemIdDeleteStatement)) { stmt =>
          sqlizer.prepareSystemIdDelete(stmt, sid)
          val results = stmt.executeBatch().toSeq
          if(results != Seq(1)) {
            errors.put(job, NoSuchRowToDelete(id))
          } else {
            deleted.put(job, id)
            logChanged(sid)
          }
        }
    }
  }

  def lookup(id: CV) =
    for {
      stmt <- managed(connection.createStatement())
      rs <- managed(stmt.executeQuery(sqlizer.selectRow(id)))
    } yield {
      if(rs.next()) Some(sqlizer.extractRow(rs))
      else None
    }

  def report =
    PostgresTransaction.JobReport(inserted.asScala, updated.asScala, deleted.asScala, elided.asScala, errors.asScala)

  def commit() {
    sqlizer.logTransactionComplete()
    connection.commit()
  }
}