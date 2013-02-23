package com.socrata.datacoordinator.service

import java.io.{IOException, InputStream, FileNotFoundException, File}
import javax.servlet.http.HttpServletRequest

import com.socrata.http.server.implicits._
import com.socrata.http.server.{HttpResponse, SocrataServerJetty, HttpService}
import com.socrata.http.server.responses._
import com.socrata.http.routing.{ExtractingRouter, RouterSet}
import com.rojoma.json.util.{AutomaticJsonCodecBuilder, JsonKey, JsonUtil}
import com.rojoma.json.io.JsonReaderException
import com.ibm.icu.text.Normalizer
import com.socrata.datacoordinator.common.soql.PostgresSoQLDataContext
import java.util.concurrent.{TimeUnit, Executors}
import org.postgresql.ds.PGSimpleDataSource
import com.socrata.datacoordinator.truth.DataContext
import com.typesafe.config.ConfigFactory
import com.socrata.datacoordinator.common.StandardDatasetMapLimits

case class Field(name: String, @JsonKey("type") typ: String)
object Field {
  implicit val jCodec = AutomaticJsonCodecBuilder[Field]
}

class Service(dataContext: DataContext, storeFile: InputStream => String, importFile: (String, String, Seq[Field]) => Unit) {
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
      importFile(norm(id), file, schema)
    } catch {
      case _: FileNotFoundException =>
        return NotFound
      case _: Exception =>
        return InternalServerError
    }

    OK
  }

  val router = RouterSet(
    ExtractingRouter[HttpService]("POST", "/upload")(doUploadFile _),
    ExtractingRouter[HttpService]("POST", "/import/?")(doImportFile _),
    ExtractingRouter[HttpService]("POST", "/replace/?")(doImportFile _)
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

object Service extends App {
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
    val dataContext: DataContext = new PostgresSoQLDataContext(
      dataSource,
      executorService,
      StandardDatasetMapLimits,
      None,
      _.asInstanceOf[org.postgresql.core.BaseConnection].getCopyAPI.copyIn(_, _)
    )
    val fileStore = new FileStore(new File("/tmp/filestore"))
    val importer = new FileImporter(fileStore.open, dataContext)
    val serv = new Service(dataContext, fileStore.store, importer.importFile)
    serv.run(port)
  } finally {
    executorService.shutdown()
  }
  executorService.awaitTermination(Long.MaxValue, TimeUnit.SECONDS)
}
