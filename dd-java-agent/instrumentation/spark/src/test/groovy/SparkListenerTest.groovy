import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.instrumentation.spark.DatadogSparkListener
import org.apache.spark.SparkConf
import org.apache.spark.scheduler.SparkListenerApplicationEnd
import org.apache.spark.scheduler.SparkListenerApplicationStart
import org.apache.spark.scheduler.SparkListenerExecutorAdded
import org.apache.spark.scheduler.SparkListenerExecutorRemoved
import org.apache.spark.scheduler.cluster.ExecutorInfo
import scala.Option
import scala.collection.immutable.HashMap

class SparkListenerTest extends AgentTestRunner {

  private getTestDatadogSparkListener() {
    def conf = new SparkConf()
    return new DatadogSparkListener(conf, "some_app_id", "some_version")
  }

  private applicationStartEvent(time=0L) {
    // Constructor of SparkListenerApplicationStart changed starting spark 3.0
    if (TestSparkComputation.getSparkVersion() < "3") {
      return new SparkListenerApplicationStart(
        "some_app_name",
        Option.apply("some_app_id"),
        time,
        "some_user",
        Option.apply("1"),
        Option.empty()
        )
    }

    return new SparkListenerApplicationStart(
      "some_app_name",
      Option.apply("some_app_id"),
      time,
      "some_user",
      Option.apply("1"),
      Option.empty(),
      Option.empty()
      )
  }

  private executorAddedEvent(time=0L, executorId="executor-0", totalCores=0) {
    new SparkListenerExecutorAdded(
      time,
      executorId,
      new ExecutorInfo(
      "some_host",
      totalCores,
      new HashMap<String, String>()
      )
      )
  }

  private executorRemovedEvent(time=0L, executorId="0") {
    new SparkListenerExecutorRemoved(
      time,
      executorId,
      "some_reason"
      )
  }

  def "compute available executor time"() {
    setup:
    def listener = getTestDatadogSparkListener()

    listener.onApplicationStart(applicationStartEvent(1000L))

    listener.onExecutorAdded(executorAddedEvent(2000L, "executor-1", 4))
    listener.onExecutorAdded(executorAddedEvent(3000L, "executor-2", 5))
    listener.onExecutorRemoved(executorRemovedEvent(4000L, "executor-2"))

    listener.onApplicationEnd(new SparkListenerApplicationEnd(5000L))

    expect:
    def expectedExecutorTime = (5000 - 2000) * 4 + (4000 - 3000) * 5
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.application"
          spanType "spark"
          assert span.tags["spark_application_metrics.available_executor_time"] == expectedExecutorTime
        }
      }
    }
  }
}
