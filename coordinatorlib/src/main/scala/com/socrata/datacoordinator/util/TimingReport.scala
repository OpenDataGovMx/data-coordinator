package com.socrata.datacoordinator.util

import org.slf4j.Logger
import scala.util.DynamicVariable
import com.rojoma.json.util.JsonUtil

trait TimingReport {
  def apply[T](name: String, kv: (String, Any)*)(f: => T): T
}

trait TransferrableContextTimingReport extends TimingReport {
  // for use when getting a worker from a thread pool
  type Context
  def context: Context
  def withContext[T](context: Context)(f: => T): T
}

trait StackedTimingReport extends TimingReport with TransferrableContextTimingReport {
  private val contextLocal = new ThreadLocal[List[String]] {
    override def initialValue = Nil
  }

  type Context = List[String]

  def context = contextLocal.get

  abstract override def apply[T](name: String, kv: (String, Any)*)(f: => T): T = {
    contextLocal.set(name :: context)
    try {
      super.apply(context.reverse.mkString("/"), kv: _*)(f)
    } finally {
      contextLocal.set(context.tail)
    }
  }

  def withContext[T](context: Context)(f: => T): T = {
    val oldContext = contextLocal.get
    contextLocal.set(context)
    try {
      f
    } finally {
      contextLocal.set(oldContext)
    }
  }
}

class LoggedTimingReport(log: Logger) extends TimingReport {
  def apply[T](name: String, kv: (String, Any)*)(f: => T): T = {
    val start = System.nanoTime()
    try {
      f
    } finally {
      val end = System.nanoTime()
      if(log.isInfoEnabled) {
        log.info("{}: {}ms; {}", name, ((end - start)/1000000).asInstanceOf[AnyRef], JsonUtil.renderJson(kv.map { case (k,v) => (k, String.valueOf(v)) }))
      }
    }
  }
}

object NoopTimingReport extends TransferrableContextTimingReport {
  def apply[T](name: String, kv: (String, Any)*)(f: => T): T = f

  type Context = Unit
  def context = ()
  def withContext[T](ctx: Unit)(f: => T) = f
}

