package datadog.trace.instrumentation.spark

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Platform
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.SparkSession
import scala.Option
import scala.collection.JavaConverters
import scala.collection.immutable.Seq
import spock.lang.IgnoreIf
import spock.lang.Unroll

@Unroll
@IgnoreIf(reason="https://issues.apache.org/jira/browse/HADOOP-18174", value = {
  Platform.isJ9()
})
class AbstractSparkStructuredStreamingTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark.enabled", "true")
  }

  private memoryStream(SparkSession sparkSession) {
    if (TestSparkComputation.sparkVersion >= '3') {
      return new MemoryStream<String>(1, sparkSession.sqlContext(), Option.empty(), Encoders.STRING())
    }

    return new MemoryStream<String>(1, sparkSession.sqlContext(), Encoders.STRING())
  }

  def "generate spark structured streaming batches"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2") // Small parallelism to speed up tests
      .appName("Sample Streaming Application")
      .getOrCreate()

    def inputStream = memoryStream(sparkSession)

    def query = inputStream
      .toDS()
      .selectExpr("value", "current_timestamp() as event_time")
      .withWatermark("event_time", "0 seconds")
      .groupBy("value")
      .count()
      .writeStream()
      .queryName("test-query")
      .outputMode("complete")
      .format("console")
      .start()

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
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2") // Small parallelism to speed up tests
      .appName("Failing Streaming Application")
      .getOrCreate()

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
}
