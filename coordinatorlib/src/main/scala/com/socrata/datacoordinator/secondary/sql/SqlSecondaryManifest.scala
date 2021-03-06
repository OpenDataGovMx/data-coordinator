package com.socrata.datacoordinator.secondary
package sql

import java.sql.Connection

import com.rojoma.simplearm.util._

import com.socrata.datacoordinator.id.DatasetId
import com.socrata.datacoordinator.id.sql._

class SqlSecondaryManifest(conn: Connection) extends SecondaryManifest {
  def readLastDatasetInfo(storeId: String, datasetId: DatasetId): Option[(Long, Option[String])] =
    using(conn.prepareStatement("SELECT version, cookie FROM secondary_manifest WHERE store_id = ? AND dataset_system_id = ?")) { stmt =>
      stmt.setString(1, storeId)
      stmt.setDatasetId(2, datasetId)
      using(stmt.executeQuery()) { rs =>
        if(rs.next()) {
          Some((rs.getLong("version"), Option(rs.getString("cookie"))))
        } else {
          None
        }
      }
    }

  def lastDataInfo(storeId: String, datasetId: DatasetId): (Long, Option[String]) =
    readLastDatasetInfo(storeId, datasetId).getOrElse {
      using(conn.prepareStatement("INSERT INTO secondary_manifest (store_id, dataset_system_id, version, cookie) VALUES (?, ?, 0, NULL)")) { stmt =>
        stmt.setString(1, storeId)
        stmt.setDatasetId(2, datasetId)
        stmt.execute()
        (0L, None)
      }
    }

  def updateDataInfo(storeId: String, datasetId: DatasetId, dataVersion: Long, cookie: Option[String]) {
    using(conn.prepareStatement("UPDATE secondary_manifest SET version = ?, cookie = ? WHERE store_id = ? AND dataset_system_id = ?")) { stmt =>
      stmt.setLong(1, dataVersion)
      cookie match {
        case Some(c) => stmt.setString(2, c)
        case None => stmt.setNull(2, java.sql.Types.VARCHAR)
      }
      stmt.setString(3, storeId)
      stmt.setDatasetId(4, datasetId)
      stmt.execute()
    }
  }

  def dropDataset(storeId: String, datasetId: DatasetId) {
    using(conn.prepareStatement("DELETE FROM secondary_manifest WHERE store_id = ? AND dataset_system_id = ?")) { stmt =>
      stmt.setString(1, storeId)
      stmt.setDatasetId(2, datasetId)
      stmt.execute()
    }
  }

  def statusOf(storeId: String, datasetId: DatasetId): Map[String, Long] = {
    using(conn.prepareStatement("SELECT store_id, version FROM secondary_manifest WHERE dataset_system_id = ?")) { stmt =>
      stmt.setDatasetId(1, datasetId)
      using(stmt.executeQuery()) { rs =>
        val result = Map.newBuilder[String, Long]
        while(rs.next()) {
          result += rs.getString("store_id") -> rs.getLong("version")
        }
        result.result()
      }
    }
  }

  def datasets(storeId: String): Map[DatasetId, Long] = {
    using(conn.prepareStatement("SELECT dataset_system_id, version FROM secondary_manifest WHERE store_id = ?")) { stmt =>
      stmt.setString(1, storeId)
      using(stmt.executeQuery()) { rs =>
        val result = Map.newBuilder[DatasetId, Long]
        while(rs.next()) {
          result += rs.getDatasetId("dataset_system_id") -> rs.getLong("version")
        }
        result.result()
      }
    }
  }

  def stores(datasetId: DatasetId): Map[String, Long] = {
    using(conn.prepareStatement("SELECT store_id, version FROM secondary_manifest WHERE dataset_system_id = ?")) { stmt =>
      stmt.setDatasetId(1, datasetId)
      using(stmt.executeQuery()) { rs =>
        val result = Map.newBuilder[String, Long]
        while(rs.next()) {
          result += rs.getString("store_id") -> rs.getLong("version")
        }
        result.result()
      }
    }
  }
}
