package com.socrata.datacoordinator.service

import java.io._
import javax.servlet.http.HttpServletRequest

import com.socrata.http.server.implicits._
import com.socrata.http.server.{HttpResponse, SocrataServerJetty, HttpService}
import com.socrata.http.server.responses._
import com.socrata.http.routing.{ExtractingRouter, RouterSet}
import com.rojoma.json.util.{AutomaticJsonCodecBuilder, JsonKey, JsonUtil}
import com.rojoma.json.io.{JsonReaderException, CompactJsonWriter}
import com.rojoma.json.ast.JObject
import com.ibm.icu.text.Normalizer
import com.socrata.datacoordinator.common.soql.{JsonSoQLDataContext, PostgresSoQLDataContext}
import java.util.concurrent.{TimeUnit, Executors}
import org.postgresql.ds.PGSimpleDataSource
import com.socrata.datacoordinator.truth.{JsonDataContext, DataReadingContext, DataWritingContext}
import com.typesafe.config.ConfigFactory
import com.socrata.datacoordinator.common.StandardDatasetMapLimits
import java.sql.Connection

case class Field(name: String, @JsonKey("type") typ: String)
object Field {
  implicit val jCodec = AutomaticJsonCodecBuilder[Field]
}

class Service(storeFile: InputStream => String,
              importFile: (String, String, Seq[Field], Option[String]) => Unit,
              datasetContents: String => (Iterator[JObject] => Unit) => Boolean)
{
  val log = org.slf4j.LoggerFactory.getLogger(classOf[Service])

  def norm(s: String) = Normalizer.normalize(s, Normalizer.NFC)

  def doUploadFile()(req: HttpServletRequest): HttpResponse = {
    try {
      val sha = storeFile(req.getInputStream)
      OK ~> Content(sha)
    } catch {
      case e: IOException =>
        log.error("IO exception while processing upload", e)
        InternalServerError
    }
  }

  def doImportFile(id: String)(req: HttpServletRequest): HttpResponse = {
    val file = Option(req.getParameter("file")).map(norm).getOrElse {
      return BadRequest ~> Content("No file")
    }
    val schema = try {
      Option(req.getParameter("schema")).map(norm).flatMap(JsonUtil.parseJson[Seq[Field]]).getOrElse {
        return BadRequest ~> Content("Cannot parse schema as an array of fields")
      }
    } catch {
      case _: JsonReaderException =>
        return BadRequest ~> Content("Cannot parse schema as JSON")
    }
    val primaryKey = try {
      Option(req.getParameter("primaryKey")).filter(_.nonEmpty).map(norm)
    }

    try {
      importFile(norm(id), file, schema, primaryKey)
    } catch {
      case _: FileNotFoundException =>
        return NotFound
      case e: Exception =>
        log.error("Unexpected exception while importing", e)
        return InternalServerError
    }

    OK
  }

  def doExportFile(id: String)(req: HttpServletRequest): HttpResponse = { resp =>
    val found = datasetContents(norm(id)) { rows =>
      resp.setContentType("application/json")
      resp.setCharacterEncoding("utf-8")
      val out = resp.getWriter
      out.write('[')
      val jsonWriter = new CompactJsonWriter(out)
      var didOne = false
      while(rows.hasNext) {
        if(didOne) out.write(',')
        else didOne = true
        jsonWriter.write(rows.next())
      }
      out.write(']')
    }
    if(!found)
      NotFound(resp)
  }

  val router = RouterSet(
    ExtractingRouter[HttpService]("POST", "/upload")(doUploadFile _),
    ExtractingRouter[HttpService]("POST", "/import/?")(doImportFile _),
    ExtractingRouter[HttpService]("GET", "/export/?")(doExportFile _)
  )

  private def handler(req: HttpServletRequest): HttpResponse = {
    router.apply(req.getMethod, req.getPathInfo.split('/').drop(1)) match {
      case Some(result) =>
        result(req)
      case None =>
        NotFound
    }
  }

  def run(port: Int) {
    val server = new SocrataServerJetty(handler, port = port)
    server.run()
  }
}

object Service extends App { self =>
  val log = org.slf4j.LoggerFactory.getLogger(classOf[Service])
  val config = ConfigFactory.load()
  val serviceConfig = config.getConfig("com.socrata.coordinator-service")
  println(serviceConfig.root.render)

  val dataSource = new PGSimpleDataSource
  dataSource.setServerName(serviceConfig.getString("database.host"))
  dataSource.setPortNumber(serviceConfig.getInt("database.port"))
  dataSource.setDatabaseName(serviceConfig.getString("database.database"))
  dataSource.setUser(serviceConfig.getString("database.username"))
  dataSource.setPassword(serviceConfig.getString("database.password"))

  val port = serviceConfig.getInt("network.port")

  val executorService = Executors.newCachedThreadPool()
  try {
    val dataContext: DataReadingContext with DataWritingContext with JsonDataContext = new PostgresSoQLDataContext with JsonSoQLDataContext {
      val dataSource = self.dataSource
      val executorService = self.executorService
      def copyIn(conn: Connection, sql: String, input: Reader): Long =
        conn.asInstanceOf[org.postgresql.core.BaseConnection].getCopyAPI.copyIn(sql, input)
      def tablespace(s: String) = Some("pg_default")
      val datasetMapLimits = StandardDatasetMapLimits
    }
    val fileStore = new FileStore(new File("/tmp/filestore"))
    val importer = new FileImporter(fileStore.open, dataContext)
    val exporter = new Exporter(dataContext)
    val serv = new Service(fileStore.store, importer.importFile, exporter.export)
    serv.run(port)
  } finally {
    executorService.shutdown()
  }
  executorService.awaitTermination(Long.MaxValue, TimeUnit.SECONDS)
}
