import datadog.trace.agent.test.AgentTestRunner
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.spark.api.java.function.VoidFunction2
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.Trigger
import org.junit.Rule
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.rule.EmbeddedKafkaRule
import org.springframework.kafka.test.utils.KafkaTestUtils

class SparkStreamingKafkaTest extends AgentTestRunner {
  static final SOURCE_TOPIC = "source"
  static final SINK_TOPIC = "sink"

  @Override
  boolean isDataStreamsEnabled() {
    return true
  }

  @Rule
  EmbeddedKafkaRule kafkaRule = new EmbeddedKafkaRule(1, false, 1, SOURCE_TOPIC, SINK_TOPIC)
  EmbeddedKafkaBroker embeddedKafka = kafkaRule.embeddedKafka

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark.enabled", "true")
    injectSysConfig("dd.integration.kafka.enabled", "true")
  }

  def "test dsm checkpoints are correctly set"() {
    setup:
    def appName = "test-app"
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.driver.bindAddress", "localhost")
      .appName(appName)
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

    // check that checkpoints were written with a service name override == "SparkAppName"
    assert TEST_DATA_STREAMS_WRITER.payloads.size() > 0
    assert TEST_DATA_STREAMS_WRITER.services.size() == 1
    assert TEST_DATA_STREAMS_WRITER.services.get(0) == appName
  }
}
