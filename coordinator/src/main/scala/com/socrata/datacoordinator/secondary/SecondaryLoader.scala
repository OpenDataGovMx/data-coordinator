package com.socrata.datacoordinator.secondary

import scala.language.existentials

import java.io.{InputStreamReader, FilenameFilter, File}
import java.net.URLClassLoader
import com.rojoma.json.util.{JsonUtil, AutomaticJsonCodecBuilder, JsonKey}
import com.rojoma.json.io.JsonReaderException
import scala.collection.immutable.VectorBuilder
import scala.util.control.ControlThrowable

case class SecondaryDescription(@JsonKey("class") className: String)
object SecondaryDescription {
  implicit val jCodec = AutomaticJsonCodecBuilder[SecondaryDescription]
}

class SecondaryLoader(parentClassLoader: ClassLoader) {
  val log = org.slf4j.LoggerFactory.getLogger(classOf[SecondaryLoader])

  def loadSecondaries(dir: File): Seq[Secondary[_]] = {
    val jars = Option(dir.listFiles(new FilenameFilter {
      def accept(dir: File, name: String): Boolean = name.endsWith(".jar")
    })).getOrElse(Array.empty).toSeq
    val result = new VectorBuilder[Secondary[_]]
    for(jar <- jars) {
      log.info("Loading secondary from " + jar.getAbsolutePath)
      try {
        val cl = new URLClassLoader(Array(jar.toURI.toURL), parentClassLoader)
        val stream = cl.getResourceAsStream("secondary-manifest.json")
        if(stream == null) throw Nope("No secondary-manifest.json in " + jar.getAbsolutePath)
        try {
          val desc = try {
            JsonUtil.readJson[SecondaryDescription](new InputStreamReader(stream, "UTF-8")).getOrElse {
              throw Nope("Unable to parse a SecondaryDescription from " + jar.getAbsolutePath)
            }
          } catch {
              case e: JsonReaderException =>
                throw Nope("Unable to parse " + jar.getAbsolutePath + " as JSON", e)
          }
          val cls =
            try { cl.loadClass(desc.className) }
            catch { case e: Exception => throw Nope("Unable to load class " + desc.className + " from " + jar.getAbsolutePath, e) }
          val ctor =
            try { cls.getConstructor() }
            catch { case e: Exception => throw Nope("Unable to find constructor for " + desc.className + " from " + jar.getAbsolutePath, e) }
          val instance =
            try { ctor.newInstance().asInstanceOf[Secondary[_]] }
            catch { case e: Exception => throw Nope("Unable to create a new instance of " + desc.className, e) }
          result += instance
        } finally {
          stream.close()
        }
      } catch {
        case Nope(msg, null) => log.warn(msg)
        case Nope(msg, ex) => log.warn(msg, ex)
      }
    }
    result.result()
  }

  private case class Nope(message: String, cause: Throwable = null) extends Throwable(message, cause) with ControlThrowable
}

case class LoadedSecondary(instance: Secondary[_])
