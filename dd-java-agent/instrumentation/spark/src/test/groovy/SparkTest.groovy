import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.instrumentation.spark.DatabricksParentContext
import datadog.trace.instrumentation.spark.DatadogSparkListener
import datadog.trace.test.util.Flaky
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.spark.deploy.SparkSubmit
import org.apache.spark.deploy.yarn.ApplicationMaster
import org.apache.spark.deploy.yarn.ApplicationMasterArguments
import org.apache.spark.sql.SparkSession
import scala.reflect.ClassTag$

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

  private ApplicationMaster createApplicationMaster(SparkSession spark) {
    // Constructor of ApplicationMaster changed starting spark 3.0
    if (spark.version() < "3") {
      return new ApplicationMaster(new ApplicationMasterArguments([] as String[]))
    }

    new ApplicationMaster(
      new ApplicationMasterArguments([] as String[]),
      spark.sparkContext().conf(),
      new YarnConfiguration()
      )
  }

  def "instrument yarn application master finish"() {
    setup:
    def spark = SparkSession.builder().config("spark.master", "local").getOrCreate()
    def am = createApplicationMaster(spark)
    am.finish(FinalApplicationStatus.FAILED, 9, "User class threw exception: org.apache.spark.sql.AnalysisException: Column 'foo' does not exist\n\tat com.datadog.spark.MySparkApp.main(MySparkApp.scala:6)")

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.application"
          resourceName "spark.application"
          spanType "spark"
          assert span.tags["error.type"] == "Spark Application Failed with exit code 9"
          assert span.tags["error.message"] == "User class threw exception: org.apache.spark.sql.AnalysisException: Column 'foo' does not exist\n"
          assert span.tags["error.stack"] == "User class threw exception: org.apache.spark.sql.AnalysisException: Column 'foo' does not exist\n\tat com.datadog.spark.MySparkApp.main(MySparkApp.scala:6)"
          errored true
          parent()
        }
      }
    }

    cleanup:
    spark.stop()
  }

  @Flaky('sometimes spark.job is the first span')
  def "generate error tags in failed spans"() {
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local")
      .getOrCreate()

    try {
      TestSparkComputation.generateTestFailingSparkComputation(sparkSession)
    }
    catch (Exception ignored) {}

    def datadogSparkListener = (DatadogSparkListener)sparkSession
      .sparkContext()
      .listenerBus()
      .findListenersByClass(ClassTag$.MODULE$.apply(DatadogSparkListener))
      .apply(0)

    blockUntilChildSpansFinished(datadogSparkListener.applicationSpan, 3)
    sparkSession.stop()

    expect:
    assertTraces(1) {
      trace(4) {
        span {
          operationName "spark.application"
          resourceName "spark.application"
          spanType "spark"
          errored true
          parent()
          assert span.tags["error.type"] == "Spark Application Failed"
          assert span.tags["error.message"] =~ /^Job aborted due to stage failure.*java.lang.NullPointerException$/
          assert span.tags["error.stack"] =~ /(?s)^org.apache.spark.SparkException.*Caused by: java.lang.NullPointerException.*$/
        }
        span {
          operationName "spark.job"
          resourceName "collect at TestSparkComputation.java:21"
          spanType "spark"
          errored true
          childOf(span(0))
          assert span.tags["error.type"] == "Spark Job Failed"
          assert span.tags["error.message"] =~ /^Job aborted due to stage failure.*java.lang.NullPointerException$/
          assert span.tags["error.stack"] =~ /(?s)^org.apache.spark.SparkException.*Caused by: java.lang.NullPointerException.*$/
        }
        span {
          operationName "spark.stage"
          resourceName "collect at TestSparkComputation.java:21"
          spanType "spark"
          errored true
          childOf(span(1))
          assert span.tags["error.type"] == "Spark Stage Failed"
          assert span.tags["error.message"] =~ /^Job aborted due to stage failure.*java.lang.NullPointerException$/
          assert span.tags["error.stack"] =~ /(?s).*\n\tat TestSparkComputation.{500,}\$/
        }
        span {
          operationName "spark.task"
          spanType "spark"
          errored true
          childOf(span(2))
          assert span.tags["error.type"] == "Spark Task Failed"
          assert span.tags["error.message"] == "java.lang.NullPointerException: null"
          assert span.tags["error.stack"] =~ /(?s)^java.lang.NullPointerException\n\tat TestSparkComputation.{500,}\$/
        }
      }
    }
  }

  def "capture SparkSubmit.runMain() errors"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .getOrCreate()

    try {
      // Generating a fake spark submit to trigger the runMain() method
      // it will fail since TestClass and test-jar.jar don't exist
      def sparkSubmit = new SparkSubmit()
      sparkSubmit.doSubmit(["--class", "TestClass", "test-jar.jar"] as String[])
    }
    catch (Exception ignored) {}

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.application"
          resourceName "spark.application"
          spanType "spark"
          errored true
          assert span.tags["error.type"] == "org.apache.spark.SparkUserAppException"
          assert span.tags["error.message"] == "User application exited with 101"
          parent()
        }
      }
    }

    cleanup:
    sparkSession.stop()
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

    cleanup:
    sparkSession.stop()
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
