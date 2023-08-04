import datadog.trace.agent.test.AgentTestRunner
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.SparkSession
import scala.Option
import scala.collection.JavaConverters
import scala.collection.immutable.Seq

class SparkStructuredStreamingTest extends AgentTestRunner {

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
      trace(4) {
        span {
          operationName "spark.batch"
          resourceName "test-query"
          spanType "spark"
          parent()
          tags {
            defaultTags()
            // Streaming tags
            "batch_id" 0
            "name" "test-query"
            "app_id" String
            "id" UUID
            "run_id" UUID
            "spark.num_input_rows" 3
            "spark.add_batch_duration" Long
            "spark.get_batch_duration" Long
            "spark.query_planing_duration" Long
            "spark.trigger_execution_duration" Long
            "spark.wal_commit_duration" Long
            "spark.input_rows_per_second" Double
            "spark.processed_rows_per_second" Double
            "spark.sink.description" ~"org.apache.spark.sql.execution.streaming.Console.*"
            "spark.source.0.description" "MemoryStream[value#1]"
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
          }
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
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(1))
        }
      }
      trace(4) {
        span {
          operationName "spark.batch"
          spanType "spark"
          assert span.tags["batch_id"] == 1
          parent()
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
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(1))
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
      trace(4) {
        span {
          operationName "spark.batch"
          resourceName "failing-query"
          spanType "spark"
          errored true
          assert span.tags["error.message"] =~ /org.apache.spark.SparkException: .*\n/
          assert span.tags["error.stack"] =~ /(?s).*Job aborted due to stage failure.*Caused by: java.lang.NullPointerException.*/
          parent()
        }
        span {
          operationName "spark.job"
          spanType "spark"
          errored true
          childOf(span(0))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          errored true
          childOf(span(1))
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
