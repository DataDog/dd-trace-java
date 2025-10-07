import datadog.trace.agent.test.InstrumentationSpecification.blockUntilChildSpansFinished
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.{
  setAsyncPropagationEnabled,
  activeSpan
}
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import slick.jdbc.H2Profile.api._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class SlickUtils(TEST_WRITER: ListWriter) {

  import SlickUtils._

  val database = Database.forURL(
    Url,
    user = Username,
    driver = "org.h2.Driver",
    keepAliveConnection = true,
    // Limit number of threads to hit Slick-specific case when we need to avoid re-wrapping
    // wrapped runnables.
    executor = AsyncExecutor("test", numThreads = 1, queueSize = 1000)
  )
  TEST_WRITER.waitUntilReported(setup())
  TEST_WRITER.clear()

  @Trace
  def setup(): DDSpan = {
    setAsyncPropagationEnabled(true)
    Await.result(
      database.run(
        sqlu"""CREATE ALIAS IF NOT EXISTS SLEEP FOR "java.lang.Thread.sleep(long)""""
      ),
      Duration.Inf
    )
    activeSpan().asInstanceOf[DDSpan]
  }

  @Trace
  def startQuery(query: String): Future[Vector[Int]] = {
    try {
      setAsyncPropagationEnabled(true)
      database.run(sql"#$query".as[Int])
    } finally {
      blockUntilChildSpansFinished(activeSpan(), 1)
    }
  }

  def getResults(future: Future[Vector[Int]]): Int = {
    Await.result(future, Duration.Inf).head
  }

}

object SlickUtils {

  val Driver              = "h2"
  val Db                  = "test"
  val Username            = "TESTUSER"
  val Url                 = s"jdbc:${Driver}:mem:${Db}"
  val TestValue           = 3
  val TestQuery           = "SELECT 3"
  val ObfuscatedTestQuery = "SELECT ?"

  val SleepQuery = "CALL SLEEP(3000)"

}
