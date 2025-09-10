package datadog.trace.instrumentation.spark

import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.test.util.Flaky
import groovy.json.JsonSlurper
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.spark.deploy.SparkSubmit
import org.apache.spark.deploy.yarn.ApplicationMaster
import org.apache.spark.deploy.yarn.ApplicationMasterArguments
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.Row
import org.apache.spark.sql.RowFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.StructType
import spock.lang.IgnoreIf

@IgnoreIf(reason="https://issues.apache.org/jira/browse/HADOOP-18174", value = {
  JavaVirtualMachine.isJ9()
})
abstract class AbstractSparkTest extends InstrumentationSpecification {
  @Override
  protected boolean isDataJobsEnabled() {
    return true
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark.enabled", "true")
    injectSysConfig("dd.integration.spark-openlineage.enabled", "true")
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
          assert span.context().getTraceId() != DDTraceId.ZERO
          assert span.context().getSamplingPriority() == PrioritySampling.USER_KEEP
          assert span.context().getPropagationTags().createTagMap()["_dd.p.dm"] == (-SamplingMechanism.DATA_JOBS).toString()
          parent()
        }
        span {
          operationName "spark.job"
          resourceName "count at TestSparkComputation.java:19"
          spanType "spark"
          errored false
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          resourceName "count at TestSparkComputation.java:19"
          spanType "spark"
          errored false
          assert span.tags["parent_stage_ids"] == "[0]"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          resourceName "distinct at TestSparkComputation.java:19"
          spanType "spark"
          errored false
          assert span.tags["parent_stage_ids"] == "[]"
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

    sparkSession
      .sparkContext()
      .listenerBus()
      .waitUntilEmpty(1000)

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
          resourceName "collect at TestSparkComputation.java:28"
          spanType "spark"
          errored true
          childOf(span(0))
          assert span.tags["error.type"] == "Spark Job Failed"
          assert span.tags["error.message"] =~ /^Job aborted due to stage failure.*java.lang.NullPointerException$/
          assert span.tags["error.stack"] =~ /(?s)^org.apache.spark.SparkException.*Caused by: java.lang.NullPointerException.*$/
        }
        span {
          operationName "spark.stage"
          resourceName "collect at TestSparkComputation.java:28"
          spanType "spark"
          errored true
          childOf(span(1))
          assert span.tags["error.type"] == "Spark Stage Failed"
          assert span.tags["error.message"] =~ /^Job aborted due to stage failure.*java.lang.NullPointerException$/
          assert span.tags["error.stack"] =~ /(?s).*\n\tat datadog.trace.instrumentation.spark.TestSparkComputation.{500,}\$/
        }
        span {
          operationName "spark.task"
          spanType "spark"
          errored true
          childOf(span(2))
          assert span.tags["error.type"] == "Spark Task Failed"
          assert span.tags["error.message"] == "java.lang.NullPointerException: null"
          assert span.tags["error.stack"] =~ /(?s)^java.lang.NullPointerException\n\tat datadog.trace.instrumentation.spark.TestSparkComputation.{500,}\$/
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
    AbstractDatadogSparkListener.finishTraceOnApplicationEnd = true
  }

  def "finish pyspark span launched with python onApplicationEnd"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .getOrCreate()

    try {
      // Generating a fake submit of pyspark-shell
      def sparkSubmit = new SparkSubmit()
      sparkSubmit.doSubmit(["--verbose", "pyspark-shell"] as String[])
    }
    catch (Exception ignored) {}
    sparkSession.stop()

    expect:
    assert AbstractDatadogSparkListener.isPysparkShell
    assert AbstractDatadogSparkListener.finishTraceOnApplicationEnd

    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.application"
          resourceName "spark.application"
          spanType "spark"
          errored true
          parent()
        }
      }
    }

    cleanup:
    AbstractDatadogSparkListener.isPysparkShell = false
    AbstractDatadogSparkListener.finishTraceOnApplicationEnd = true
  }

  def "generate databricks spans"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local")
      .config("spark.default.parallelism", "2") // Small parallelism to speed up tests
      .config("spark.sql.shuffle.partitions", "2")
      .config("spark.databricks.sparkContextId", "some_id")
      .config("spark.databricks.clusterUsageTags.clusterName", "job-1234-run-8765-Job_cluster")
      .getOrCreate()

    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.id", "1234")
    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.runId", "9012")
    sparkSession.sparkContext().setLocalProperty("spark.jobGroup.id", "0000_job-3456-run-7890-action-0000")
    sparkSession.sparkContext().setLocalProperty("spark.databricks.workload.id", "01-123-456")
    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.parentRunId", "5678")
    sparkSession.sparkContext().setLocalProperty("spark.databricks.clusterUsageTags.clusterName", "job-1234-run-901-Job_cluster")
    TestSparkComputation.generateTestSparkComputation(sparkSession)

    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.id", null)
    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.runId", null)
    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.parentRunId", null)
    TestSparkComputation.generateTestSparkComputation(sparkSession)

    sparkSession.sparkContext().setLocalProperty("spark.jobGroup.id", null)
    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.parentRunId", null)
    sparkSession.sparkContext().setLocalProperty("spark.databricks.clusterUsageTags.clusterName", null)
    TestSparkComputation.generateTestSparkComputation(sparkSession)

    sparkSession.sparkContext().setLocalProperty("spark.databricks.workload.id", null)
    TestSparkComputation.generateTestSparkComputation(sparkSession)

    expect:
    assertTraces(4) {
      trace(3) {
        span {
          operationName "spark.job"
          spanType "spark"
          traceId 8944764253919609482G
          parentSpanId 15104224823446433673G
          assert span.context().getSamplingPriority() == PrioritySampling.USER_KEEP
          assert span.context().getPropagationTags().createTagMap()["_dd.p.dm"] == (-SamplingMechanism.DATA_JOBS).toString()
          assert span.tags["databricks_job_id"] == "1234"
          assert span.tags["databricks_job_run_id"] == "5678"
          assert span.tags["databricks_task_run_id"] == "9012"
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(0))
        }
      }
      trace(3) {
        span {
          operationName "spark.job"
          spanType "spark"
          traceId 5240384461065211484G
          parentSpanId 14128229261586201946G
          assert span.context().getSamplingPriority() == PrioritySampling.USER_KEEP
          assert span.context().getPropagationTags().createTagMap()["_dd.p.dm"] == (-SamplingMechanism.DATA_JOBS).toString()
          assert span.tags["databricks_job_id"] == "3456"
          assert span.tags["databricks_job_run_id"] == "901"
          assert span.tags["databricks_task_run_id"] == "7890"
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(0))
        }
      }
      trace(3) {
        span {
          operationName "spark.job"
          spanType "spark"
          traceId 2235374731114184741G
          parentSpanId 8956125882166502063G
          assert span.context().getSamplingPriority() == PrioritySampling.USER_KEEP
          assert span.context().getPropagationTags().createTagMap()["_dd.p.dm"] == (-SamplingMechanism.DATA_JOBS).toString()
          assert span.tags["databricks_job_id"] == "123"
          assert span.tags["databricks_job_run_id"] == "8765"
          assert span.tags["databricks_task_run_id"] == "456"
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(0))
        }
      }
      trace(3) {
        span {
          operationName "spark.job"
          spanType "spark"
          parent()
          assert span.context().getSamplingPriority() == PrioritySampling.USER_KEEP
          assert span.context().getPropagationTags().createTagMap()["_dd.p.dm"] == (-SamplingMechanism.DATA_JOBS).toString()
          assert span.tags["databricks_job_id"] == null
          assert span.tags["databricks_job_run_id"] == "8765"
          assert span.tags["databricks_task_run_id"] == null
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
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

  private Dataset<Row> generateSampleDataframe(SparkSession spark) {
    def structType = new StructType()
    structType = structType.add("col", "String", false)

    def rows = new ArrayList<Row>()
    rows.add(RowFactory.create("value"))
    spark.createDataFrame(rows, structType)
  }

  def "generate spark sql spans"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2")
      .getOrCreate()

    def df = generateSampleDataframe(sparkSession)
    df.coalesce(1).count()
    sparkSession.stop()

    expect:
    assertTraces(1) {
      trace(4) {
        span {
          operationName "spark.application"
          spanType "spark"
          parent()
        }
        span {
          operationName "spark.sql"
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
        }
      }
    }
  }

  def "generate spark sql spans on databricks"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2")
      .config("spark.databricks.sparkContextId", "some_id")
      .config("spark.databricks.clusterUsageTags.clusterName", "job-1234-run-5678-Job_cluster")
      .getOrCreate()

    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.id", "1234")
    sparkSession.sparkContext().setLocalProperty("spark.databricks.job.runId", "9012")

    def df = generateSampleDataframe(sparkSession)
    df.coalesce(1).count()
    sparkSession.stop()

    expect:
    assertTraces(1) {
      trace(3) {
        span {
          operationName "spark.sql"
          spanType "spark"
          traceId 8944764253919609482G
          parentSpanId 15104224823446433673G
          assert span.context().getSamplingPriority() == PrioritySampling.USER_KEEP
          assert span.context().getPropagationTags().createTagMap()["_dd.p.dm"] == (-SamplingMechanism.DATA_JOBS).toString()
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(1))
        }
      }
    }
  }

  def "add custom tags to spark spans"() {
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2")
      .getOrCreate()

    def df = generateSampleDataframe(sparkSession)

    sparkSession.sparkContext().setLocalProperty("spark.datadog.tags.tag_1", "value_1")
    sparkSession.sparkContext().setLocalProperty("spark.datadog.tags.tag_2", "value_2")
    df.coalesce(1).count()

    sparkSession.sparkContext().setLocalProperty("spark.datadog.tags.tag_1", "value_11")
    sparkSession.sparkContext().setLocalProperty("spark.datadog.tags.tag_2", null)
    df.coalesce(1).count()
    sparkSession.stop()

    expect:
    assertTraces(1) {
      trace(7) {
        span {
          operationName "spark.application"
          spanType "spark"
          assert span.tags["tag_1"] == null
          assert span.tags["tag_2"] == null
          parent()
        }
        span {
          operationName "spark.sql"
          spanType "spark"
          assert span.tags["tag_1"] == "value_11"
          assert span.tags["tag_2"] == null
          childOf(span(0))
        }
        span {
          operationName "spark.job"
          spanType "spark"
          assert span.tags["tag_1"] == "value_11"
          assert span.tags["tag_2"] == null
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          assert span.tags["tag_1"] == "value_11"
          assert span.tags["tag_2"] == null
          childOf(span(2))
        }
        span {
          operationName "spark.sql"
          spanType "spark"
          assert span.tags["tag_1"] == "value_1"
          assert span.tags["tag_2"] == "value_2"
          childOf(span(0))
        }
        span {
          operationName "spark.job"
          spanType "spark"
          assert span.tags["tag_1"] == "value_1"
          assert span.tags["tag_2"] == "value_2"
          childOf(span(4))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          assert span.tags["tag_1"] == "value_1"
          assert span.tags["tag_2"] == "value_2"
          childOf(span(5))
        }
      }
    }
  }

  def "set the proper databricks service"(String ddService, String clusterName, String clusterAllTags, String expectedService) {
    setup:
    if (ddService != null) {
      injectSysConfig("dd.service", ddService)
    }

    def builder = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.databricks.sparkContextId", "some_id")

    if (clusterName) {
      builder.config("spark.databricks.clusterUsageTags.clusterName", clusterName)
    }
    if (clusterAllTags) {
      builder.config("spark.databricks.clusterUsageTags.clusterAllTags", clusterAllTags)
    }

    when:
    def sparkSession = builder.getOrCreate()
    def df = generateSampleDataframe(sparkSession)
    df.coalesce(1).count()
    sparkSession.stop()

    then:
    assertTraces(1) {
      trace(3) {
        span {
          operationName "spark.sql"
          spanType "spark"
          assert span.serviceName ==~ expectedService
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(0))
          assert span.serviceName ==~ expectedService
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(1))
          assert span.serviceName ==~ expectedService
        }
      }
    }

    where:
    ddService | clusterName         | clusterAllTags                                                                         | expectedService
    "foobar"  | "some_cluster_name" | """[{"key": "foo"}, {"key": "RunName", "value": "some_run_name"}]"""                   | "(?!.*databricks).*"
    null      | "some_cluster_name" | """[{"key": "foo"}, {"key": "RunName", "value": "some_run_name"}]"""                   | "databricks.job-cluster.some_run_name"
    null      | "some_cluster_name" | """[{"key":"RunName","value":"some_run_name_9975a7ba-5e04-11ee-8c99-0242ac120002"}]""" | "databricks.job-cluster.some_run_name"
    null      | "some_cluster_name" | """invalid_json"""                                                                     | "databricks.all-purpose-cluster.some_cluster_name"
    null      | null                | null                                                                                   | "(?!.*databricks).*"
  }

  def "set the proper spark service name"(String ddService, boolean sparkAppNameAsService, String appName, boolean isRunningOnDatabricks, String expectedService) {
    setup:
    if (ddService != null) {
      injectSysConfig("dd.service", ddService)
    }
    if (sparkAppNameAsService) {
      injectSysConfig("spark.app-name-as-service", sparkAppNameAsService.toString())
    }

    def builder = SparkSession.builder()
      .config("spark.master", "local[2]")

    if (appName != null) {
      builder.config("spark.app.name", appName)
    }

    if (isRunningOnDatabricks) {
      builder.config("spark.databricks.sparkContextId", "some_id")
    }

    when:
    def sparkSession = builder.getOrCreate()
    def df = generateSampleDataframe(sparkSession)
    df.coalesce(1).count()
    sparkSession.stop()

    then:
    def expectedSize = 4
    if (isRunningOnDatabricks) {
      expectedSize = 3
    }

    assertTraces(1) {
      trace(expectedSize) {
        if (!isRunningOnDatabricks) {
          span {
            operationName "spark.application"
            spanType "spark"
            assert span.serviceName ==~ expectedService
          }
        }
        span {
          operationName "spark.sql"
          spanType "spark"
          assert span.serviceName ==~ expectedService
        }
        span {
          operationName "spark.job"
          spanType "spark"
          assert span.serviceName ==~ expectedService
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          assert span.serviceName ==~ expectedService
        }
      }
    }

    where:
    ddService | sparkAppNameAsService | appName    | isRunningOnDatabricks | expectedService
    "foobar"  | true                  | "some_app" | false                 | "(?!.*some_app).*"
    "spark"   | true                  | "some_app" | false                 | "some_app"
    "hadoop"  | true                  | "some_app" | false                 | "some_app"
    null      | true                  | "some_app" | true                  | "(?!.*some_app).*"
    null      | true                  | "some_app" | false                 | "some_app"
    null      | false                 | "some_app" | false                 | "(?!.*some_app).*"
    null      | true                  | null       | false                 | "(?!.*some_app).*"
  }


  boolean isJsonValid(String jsonString) {
    try {
      new JsonSlurper().parseText(jsonString)
      return true
    } catch (Exception ignored) {
      return false
    }
  }

  def "compute the SQL query plan"() {
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2")
      .getOrCreate()

    def df = generateSampleDataframe(sparkSession)
    def ds = df.coalesce(1).as(Encoders.STRING())
    TestSparkComputation.applyIdentityMapFunction(ds)
      .filter("value > 0")
      .count()
    sparkSession.stop()

    expect:
    assertTraces(1) {
      trace(4) {
        span {
          operationName "spark.application"
          spanType "spark"
        }
        span {
          operationName "spark.sql"
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          // Exact SQL Plan changes depending on the spark version
          assert span.tags["_dd.spark.sql_plan"] =~ /.*HashAggregate.*Filter.*SerializeFromObject.*MapElements.*DeserializeToObject.*LocalTableScan.*/
          assert isJsonValid(span.tags["_dd.spark.sql_plan"].toString())
        }
      }
    }
  }

  def "redact application parameters"() {
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local")
      .config("spark.database.password", "value")
      .config("database.secret", "value")
      .config("spark.DD-API-KEY", "value")
      .config("spark.shuffle.compress", "false")
      .getOrCreate()

    sparkSession.stop()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.application"
          spanType "spark"
          assert span.tags["config.spark_database_password"] == "[redacted]"
          assert span.tags["config.database_secret"] == "[redacted]"
          assert span.tags["config.spark_DD-API-KEY"] == "[redacted]"
          assert span.tags["config.spark_shuffle_compress"] == "false"
        }
      }
    }
  }
}
