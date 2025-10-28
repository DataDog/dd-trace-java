package datadog.trace.instrumentation.spark

import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.streaming.StreamingQuery
import scala.Option
import scala.collection.JavaConverters
import scala.collection.immutable.Seq
import spock.lang.IgnoreIf

@IgnoreIf(reason="https://issues.apache.org/jira/browse/HADOOP-18174", value = {
  JavaVirtualMachine.isJ9()
})
class AbstractSparkStructuredStreamingTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark.enabled", "true")
    injectSysConfig("dd.integration.spark-openlineage.enabled", "true")
  }

  private SparkSession createSparkSession(String appName) {
    return SparkSession.builder()
      .appName(appName)
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2")
      .getOrCreate()
  }

  private SparkSession createDatabricksSparkSession(String appName) {
    return SparkSession.builder()
      .appName(appName)
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2")
      .config("spark.databricks.sparkContextId", "3291395623902517763")
      .config("spark.databricks.job.id", "3822225623902514353")
      .config("spark.databricks.job.parentRunId", "3851395623902519743")
      .config("spark.databricks.job.runId", "3851395623902519743")
      .getOrCreate()
  }

  private memoryStream(SparkSession sparkSession) {
    if (TestSparkComputation.sparkVersion >= '3') {
      return new MemoryStream<String>(1, sparkSession.sqlContext(), Option.empty(), Encoders.STRING())
    }

    return new MemoryStream<String>(1, sparkSession.sqlContext(), Encoders.STRING())
  }

  private StreamingQuery generateTestStreamingComputation(Dataset<String> dataSet) {
    return dataSet
      .selectExpr("value", "current_timestamp() as event_time")
      .withWatermark("event_time", "0 seconds")
      .groupBy("value")
      .count()
      .writeStream()
      .queryName("test-query")
      .outputMode("complete")
      .format("console")
      .start()
  }

  def "generate spark structured streaming batches"() {
    setup:
    def sparkSession = createSparkSession("Sample Streaming Application")

    def inputStream = memoryStream(sparkSession)
    def query = generateTestStreamingComputation(inputStream.toDS())

    inputStream.addData(JavaConverters.asScalaBuffer(["foo", "foo", "bar"]).toSeq() as Seq<String>)
    query.processAllAvailable()
    inputStream.addData(JavaConverters.asScalaBuffer(["foo", "bar"]).toSeq() as Seq<String>)
    query.processAllAvailable()

    query.stop()
    sparkSession.stop()

    expect:
    assertTraces(2) {
      trace(5) {
        span {
          operationName "spark.streaming_batch"
          resourceName "test-query"
          spanType "spark"
          parent()
          assert span.context().getSamplingPriority() == PrioritySampling.USER_KEEP
          assert span.context().getPropagationTags().createTagMap()["_dd.p.dm"] == (-SamplingMechanism.DATA_JOBS).toString()
          tags {
            defaultTags()
            // Streaming tags
            "streaming_query.batch_id" 0
            "streaming_query.name" "test-query"
            "app_id" String
            "streaming_query.id" UUID
            "streaming_query.run_id" UUID
            "spark.num_input_rows" 3
            "spark.add_batch_duration" Long
            "spark.get_batch_duration" Long
            "spark.query_planing_duration" Long
            "spark.trigger_execution_duration" Long
            "spark.wal_commit_duration" Long
            "spark.input_rows_per_second" Double
            "spark.processed_rows_per_second" Double
            "spark.sink.description" ~"org.apache.spark.sql.execution.streaming.Console.*"
            "spark.source.0.description" ~"MemoryStream.*"
            "spark.source.0.end_offset" String
            "spark.source.0.input_rows_per_second" Double
            "spark.source.0.num_input_rows" 3
            "spark.source.0.processed_rows_per_second" Double
            "spark.state.0.memory_used_bytes" Long
            "spark.state.0.num_rows_total" 2
            "spark.state.0.num_rows_updated" 2
            "spark.event_time.watermark" Long
            "spark.event_time.max" Long
            "spark.event_time.min" Long
            if (TestSparkComputation.sparkVersion >= '3') {
              "spark.latest_offset_duration" Long
            }
            // In non-databricks running environment, SpanLinks should be absent.
            assert tag(DDTags.SPAN_LINKS) == null

            // Regular spark tags
            "spark.available_executor_time" Long
            "spark.disk_bytes_spilled" Long
            "spark.executor_cpu_time" Long
            "spark.executor_deserialize_cpu_time" Long
            "spark.executor_deserialize_time" Long
            "spark.executor_run_time" Long
            "spark.input_bytes" Long
            "spark.input_records" Long
            "spark.jvm_gc_time" Long
            "spark.memory_bytes_spilled" Long
            "spark.output_bytes" Long
            "spark.output_records" Long
            "spark.peak_execution_memory" Long
            "spark.result_serialization_time" Long
            "spark.result_size" Long
            "spark.skew_time" Long
            "spark.shuffle_read_bytes" Long
            "spark.shuffle_read_bytes_local" Long
            "spark.shuffle_read_bytes_remote" Long
            "spark.shuffle_read_bytes_remote_to_disk" Long
            "spark.shuffle_read_fetch_wait_time" Long
            "spark.shuffle_read_records" Long
            "spark.shuffle_write_bytes" Long
            "spark.shuffle_write_records" Long
            "spark.shuffle_write_time" Long
            "spark.task_completed_count" Long
            "spark.task_failed_count" Long
            "spark.task_retried_count" Long
            "spark.task_with_output_count" Long

            // Config tags
            "config.spark_version" String
            "config.spark_app_id" String
            "config.spark_app_name" String
            "config.spark_jobGroup_id" String
            "config.spark_job_description" String
            "config.spark_master" String
            "config.spark_sql_execution_id" String
            "config.spark_sql_shuffle_partitions" String
            "config.sql_streaming_queryId" String
            "config.streaming_sql_batchId" String
            if (TestSparkComputation.sparkVersion >= '3') {
              "config.spark_app_startTime" String
            }
          }
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
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
        }
      }
      trace(5) {
        span {
          operationName "spark.streaming_batch"
          spanType "spark"
          assert span.tags["streaming_query.batch_id"] == 1
          assert span.context().getSamplingPriority() == PrioritySampling.USER_KEEP
          assert span.context().getPropagationTags().createTagMap()["_dd.p.dm"] == (-SamplingMechanism.DATA_JOBS).toString()
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
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
        }
      }
    }
  }

  def "handle failure during streaming processing"() {
    setup:
    def sparkSession = createSparkSession("Failing Streaming Application")

    def inputStream = memoryStream(sparkSession)
    def query = TestSparkComputation.generateTestFailingStreamingComputation(inputStream.toDS())

    try {
      inputStream.addData(JavaConverters.asScalaBuffer(["foo", "bar"]).toSeq() as Seq<String>)
      query.processAllAvailable()
    }
    catch (Exception ignored) {}
    sparkSession.stop()

    assertTraces(1) {
      trace(5, true) {
        span {
          operationName "spark.job"
          spanType "spark"
          errored true
          childOf(span(1))
        }
        span {
          operationName "spark.sql"
          spanType "spark"
          childOf(span(3))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          errored true
          childOf(span(0))
        }
        span {
          operationName "spark.streaming_batch"
          resourceName "failing-query"
          spanType "spark"
          errored true
          assert span.tags["error.message"] =~ /org.apache.spark.SparkException: .*\n/
          assert span.tags["error.stack"] =~ /(?s).*Job aborted due to stage failure.*Caused by: java.lang.NullPointerException.*/
          parent()
        }
        span {
          operationName "spark.task"
          spanType "spark"
          errored true
          childOf(span(2))
        }
      }
    }
  }

  def "add span links from spark.streaming_batch to databricks.task.execution if applicable"() {
    setup:
    def sparkSession = createDatabricksSparkSession("Example App")

    def inputStream = memoryStream(sparkSession)
    def query = generateTestStreamingComputation(inputStream.toDS())

    inputStream.addData(JavaConverters.asScalaBuffer(["foo", "foo", "bar"]).toSeq() as Seq<String>)
    query.processAllAvailable()

    query.stop()
    sparkSession.stop()

    expect:
    assertTraces(1) {
      trace(5) {
        span {
          operationName "spark.streaming_batch"
          resourceName "test-query"
          spanType "spark"
          parent()
          links({
            link(DDTraceId.from((long)12052652441736835200), (long)-6394091631972716416)
          })
        }
        span {
          operationName "spark.sql"
          spanType "spark"
          childOf(span(0))
          assert !span.tags.containsKey(DDTags.SPAN_LINKS)
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
          assert !span.tags.containsKey(DDTags.SPAN_LINKS)
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assert !span.tags.containsKey(DDTags.SPAN_LINKS)
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assert !span.tags.containsKey(DDTags.SPAN_LINKS)
        }
      }
    }
  }
}
