import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.instrumentation.spark.DatabricksParentContext
import org.apache.spark.sql.SparkSession

class SparkTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark.enabled", "true")
  }

  def "generate application span with child job and stages"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local")
      .config("spark.default.parallelism", "2") // Small parallelism to speed up tests
      .config("spark.sql.shuffle.partitions", "2")
      .appName("Sample Spark App")
      .getOrCreate()

    TestSparkComputation.generateTestSparkComputation(sparkSession)

    sparkSession.stop()

    expect:
    assertTraces(1) {
      trace(4) {
        span {
          operationName "spark.application"
          resourceName "spark.application"
          spanType "spark"
          errored false
          parent()
        }
        span {
          operationName "spark.job"
          resourceName "count at TestSparkComputation.java:12"
          spanType "spark"
          errored false
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          resourceName "count at TestSparkComputation.java:12"
          spanType "spark"
          errored false
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          resourceName "distinct at TestSparkComputation.java:12"
          spanType "spark"
          errored false
          childOf(span(1))
        }
      }
    }
  }

  def "generate databricks spans"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local")
      .config("spark.default.parallelism", "2") // Small parallelism to speed up tests
      .config("spark.sql.shuffle.partitions", "2")
      .config("spark.databricks.sparkContextId", "some_id")
      .getOrCreate()

    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.id", "1234")
    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.runId", "9012")
    sparkSession.sparkContext().setLocalProperty("spark.databricks.clusterUsageTags.clusterName", "job-1234-run-5678-Job_cluster")
    TestSparkComputation.generateTestSparkComputation(sparkSession)

    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.id", null)
    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.runId", null)
    sparkSession.sparkContext().setLocalProperty("spark.databricks.clusterUsageTags.clusterName", null)
    TestSparkComputation.generateTestSparkComputation(sparkSession)

    expect:
    assertTraces(2) {
      trace(3) {
        span {
          operationName "spark.job"
          resourceName "count at TestSparkComputation.java:12"
          spanType "spark"
          errored false
          traceId 8944764253919609482G
          parentSpanId 15104224823446433673G
        }
        span {
          operationName "spark.stage"
          resourceName "count at TestSparkComputation.java:12"
          spanType "spark"
          errored false
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          resourceName "distinct at TestSparkComputation.java:12"
          spanType "spark"
          errored false
          childOf(span(0))
        }
      }
      trace(3) {
        span {
          operationName "spark.job"
          resourceName "count at TestSparkComputation.java:12"
          spanType "spark"
          errored false
          parent()
        }
        span {
          operationName "spark.stage"
          resourceName "count at TestSparkComputation.java:12"
          spanType "spark"
          errored false
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          resourceName "distinct at TestSparkComputation.java:12"
          spanType "spark"
          errored false
          childOf(span(0))
        }
      }
    }
  }

  def "compute the databricks parent context"() {
    setup:
    def contextWithJobRunId = new DatabricksParentContext("1234", "5678", "9012")
    def contextWithoutJobRunId = new DatabricksParentContext("1234", null, "9012")
    def contextWithoutJobId = new DatabricksParentContext(null, "5678", "9012")
    def contextWithoutTaskRunId = new DatabricksParentContext(null, "5678", null)

    expect:
    contextWithJobRunId.getTraceId() == DDTraceId.from("8944764253919609482")
    contextWithJobRunId.getSpanId() == DDSpanId.from("15104224823446433673")

    contextWithoutJobRunId.getTraceId() == DDTraceId.from("15104224823446433673")
    contextWithoutJobRunId.getSpanId() == DDSpanId.from("15104224823446433673")

    contextWithoutJobId.getTraceId() == DDTraceId.ZERO
    contextWithoutJobId.getSpanId() == DDSpanId.ZERO

    contextWithoutTaskRunId.getTraceId() == DDTraceId.ZERO
    contextWithoutTaskRunId.getSpanId() == DDSpanId.ZERO
  }
}
