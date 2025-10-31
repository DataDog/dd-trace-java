package datadog.trace.instrumentation.weaver

import sbt.testing.*
import weaver.framework.CatsFingerprints.SuiteFingerprint
import weaver.framework.{CatsEffect, LoggedEvent}

import java.io.PrintStream
import scala.jdk.CollectionConverters.*

object WeaverIntegrationTestRunner {
  @de.thetaphi.forbiddenapis.SuppressForbidden
  def runTests(testNames: java.util.List[String]): Unit = {
    class WeaverTestEventHandler() extends EventHandler {
      val events = scala.collection.mutable.ListBuffer.empty[sbt.testing.Event]

      override def handle(event: Event): Unit = synchronized {
        val _ = events.append(event)
      }
    }

    class WeaverTestLogger() extends Logger {
      val logs = scala.collection.mutable.ListBuffer.empty[LoggedEvent]

      private def add(event: LoggedEvent): Unit = synchronized {
        val _ = logs.append(event)
      }

      override def ansiCodesSupported(): Boolean = false

      override def error(msg: String): Unit =
        add(LoggedEvent.Error(msg))

      override def warn(msg: String): Unit =
        add(LoggedEvent.Warn(msg))

      override def info(msg: String): Unit =
        add(LoggedEvent.Info(msg))

      override def debug(msg: String): Unit =
        add(LoggedEvent.Debug(msg))

      override def trace(t: Throwable): Unit =
        add(LoggedEvent.Trace(t))
    }

    val framework = new CatsEffect(new PrintStream(System.out))
    val runner    = framework.runner(Array.empty, Array.empty, getClass.getClassLoader)
    val scalaTestNames: List[String] = testNames.asScala.toList
    val taskDefs: Array[TaskDef] = scalaTestNames.map { name =>
      new TaskDef(name, SuiteFingerprint, false, Array(new SuiteSelector()))
    }.toArray
    val tasks        = runner.tasks(taskDefs)
    val eventHandler = new WeaverTestEventHandler()
    val logger       = new WeaverTestLogger()
    tasks.foreach(_.execute(eventHandler, Array(logger)))
    logger.logs.foreach {
      case LoggedEvent.Error(msg) => println(s"$msg")
      case LoggedEvent.Warn(msg)  => println(s"$msg")
      case LoggedEvent.Info(msg)  => println(s"$msg")
      case LoggedEvent.Debug(msg) => println(s"$msg")
      case LoggedEvent.Trace(t)   => t.printStackTrace()
    }
  }
}
