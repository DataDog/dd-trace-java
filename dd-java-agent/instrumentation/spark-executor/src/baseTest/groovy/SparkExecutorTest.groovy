import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.spark.api.java.function.VoidFunction2
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.RowFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types.StructType
import org.junit.ClassRule
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.rule.EmbeddedKafkaRule
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared


class SparkExecutorTest extends AgentTestRunner {
  static final SOURCE_TOPIC = "source"
  static final SINK_TOPIC = "sink"

  @Shared
  @ClassRule
  EmbeddedKafkaRule kafkaRule = new EmbeddedKafkaRule(1, false, 1, SOURCE_TOPIC, SINK_TOPIC)
  EmbeddedKafkaBroker embeddedKafka = kafkaRule.embeddedKafka

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark-executor.enabled", "true")
    injectSysConfig("dd.integration.spark.enabled", "true")
    injectSysConfig("dd.integration.kafka.enabled", "true")
    injectSysConfig("dd.data.streams.enabled", "true")
    injectSysConfig("dd.trace.debug", "true")
  }

  private Dataset<Row> generateSampleDataframe(SparkSession spark) {
    def structType = new StructType()
    structType = structType.add("col", "String", false)

    def rows = new ArrayList<Row>()
    rows.add(RowFactory.create("value"))
    spark.createDataFrame(rows, structType)
  }

  def "test dsm service name override"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.driver.bindAddress", "localhost")
      //      .config("spark.sql.shuffle.partitions", "2")
      .appName("test-app")
      .getOrCreate()

    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new DefaultKafkaProducerFactory<Integer, String>(producerProps).createProducer()

    when:
    for (int i = 0; i < 100; i++) {
      producer.send(new ProducerRecord<>(SOURCE_TOPIC, i, i.toString()))
    }
    producer.flush()

    def df = sparkSession
      .readStream()
      .format("kafka")
      .option("kafka.bootstrap.servers", embeddedKafka.getBrokersAsString())
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .option("subscribe", SOURCE_TOPIC)
      .load()

    def query = df
      .selectExpr("CAST(key AS STRING) as key", "CAST(value AS STRING) as value")
      .writeStream()
      .format("kafka")
      .option("kafka.bootstrap.servers", embeddedKafka.getBrokersAsString())
      .option("checkpointLocation", "/tmp/" + System.currentTimeMillis().toString())
      .option("topic", SINK_TOPIC)
      .trigger(Trigger.Once())
      .foreachBatch(new VoidFunction2<Dataset<Row>, Long>() {
        @Override
        void call(Dataset<Row> rowDataset, Long aLong) throws Exception {
          rowDataset.show()
          rowDataset.write()
        }
      })
      .start()

    query.processAllAvailable()

    then:
    query.stop()
    producer.close()
  }

  def "generate spark task run spans"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.driver.bindAddress", "localhost")
      .config("spark.sql.shuffle.partitions", "2")
      .appName("test-app")
      .getOrCreate()

    def df = generateSampleDataframe(sparkSession)
    df.count()
    sparkSession.stop()

    expect:
    assertTraces(2) {
      trace(1) {
        span {
          operationName "spark.task"
          resourceName "spark.task"
          parent()
          tags {
            "$Tags.COMPONENT" "spark"
            "task_id" 0
            "task_thread_name" String
            "stage_id" 0
            "stage_attempt_id" 0
            "job_id" 0
            "app_id" String
            "application_name" "test-app"

            // Spark metrics
            "spark.executor_deserialize_time" Long
            "spark.executor_deserialize_cpu_time" Long
            "spark.executor_run_time" Long
            "spark.executor_cpu_time" Long
            "spark.result_size" Long
            "spark.jvm_gc_time" Long
            "spark.result_serialization_time" Long
            "spark.memory_bytes_spilled" Long
            "spark.disk_bytes_spilled" Long
            "spark.peak_execution_memory" Long
            "spark.input_bytes" Long
            "spark.input_records" Long
            "spark.output_bytes" Long
            "spark.output_records" Long
            "spark.shuffle_read_bytes" Long
            "spark.shuffle_read_bytes_local" Long
            "spark.shuffle_read_bytes_remote" Long
            "spark.shuffle_read_bytes_remote_to_disk" Long
            "spark.shuffle_read_fetch_wait_time" Long
            "spark.shuffle_read_records" Long
            "spark.shuffle_write_bytes" Long
            "spark.shuffle_write_records" Long
            "spark.shuffle_write_time" Long
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          operationName "spark.task"
          resourceName "spark.task"
          parent()
          tags {
            "$Tags.COMPONENT" "spark"
            "task_id" 1
            "task_thread_name" String
            "stage_id" Integer
            "stage_attempt_id" 0
            "job_id" Integer
            "app_id" String
            "application_name" "test-app"

            // Spark metrics
            "spark.executor_deserialize_time" Long
            "spark.executor_deserialize_cpu_time" Long
            "spark.executor_run_time" Long
            "spark.executor_cpu_time" Long
            "spark.result_size" Long
            "spark.jvm_gc_time" Long
            "spark.result_serialization_time" Long
            "spark.memory_bytes_spilled" Long
            "spark.disk_bytes_spilled" Long
            "spark.peak_execution_memory" Long
            "spark.input_bytes" Long
            "spark.input_records" Long
            "spark.output_bytes" Long
            "spark.output_records" Long
            "spark.shuffle_read_bytes" Long
            "spark.shuffle_read_bytes_local" Long
            "spark.shuffle_read_bytes_remote" Long
            "spark.shuffle_read_bytes_remote_to_disk" Long
            "spark.shuffle_read_fetch_wait_time" Long
            "spark.shuffle_read_records" Long
            "spark.shuffle_write_bytes" Long
            "spark.shuffle_write_records" Long
            "spark.shuffle_write_time" Long
            defaultTags()
          }
        }
      }
    }
  }
}
