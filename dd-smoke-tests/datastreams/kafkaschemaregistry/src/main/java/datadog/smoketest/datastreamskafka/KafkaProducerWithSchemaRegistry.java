package datadog.smoketest.datastreams.kafkaschemaregistry;

import java.util.Properties;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import datadog.smoketest.datastreams.kafkaschemaregistry.Message.MyMessage;

public class KafkaProducerWithSchemaRegistry {
  public static void main(String[] args) {
    produce();
  }
  public static void produce() {
    String topicName = "no_schema_topic";
    String apiKey = System.getenv("CONFLUENT_API_KEY");
    String apiSecret = System.getenv("CONFLUENT_API_SECRET");
    String bootstrapServers = System.getenv("CONFLUENT_SERVERS");
    String schemaRegistryUrl = System.getenv("SCHEMA_REGISTRY_URL");

    Properties properties = new Properties();
    properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.setProperty(
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.setProperty(
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer");
    properties.setProperty("schema.registry.url", schemaRegistryUrl);
    properties.setProperty(
        "sasl.jaas.config",
        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
            + apiKey
            + "\" password=\""
            + apiSecret
            + "\";");
    properties.setProperty("sasl.mechanism", "PLAIN");
    properties.setProperty("security.protocol", "SASL_SSL");
    properties.setProperty("basic.auth.credentials.source", "USER_INFO");
    properties.setProperty("basic.auth.user.info", System.getenv("SCHEMA_REGISTRY_AUTH"));

    Producer<String, MyMessage> producer =
        new org.apache.kafka.clients.producer.KafkaProducer<>(properties);

    try {
      for (int i = 1; i <= 20; i++) {
        MyMessage message = MyMessage.newBuilder()
            .setId("1")
            .setValue("Hello from Protobuf!")
            .build();

        ProducerRecord<String, MyMessage> record
            = new ProducerRecord<String, MyMessage>(topicName, "testkey", message);
        producer.send(record);
        Thread.sleep(1500);
        System.out.println("produced message");
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      producer.close();
    }
  }
}
