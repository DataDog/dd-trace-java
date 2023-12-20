package datadog.smoketest.datastreams.kafkaschemaregistry;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.util.Properties;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

public class KafkaProducerWithSchemaRegistry {
  public static void main(String[] args) {
    String schemaRegistryUrl = System.getenv("SCHEMA_REGISTRY_URL");
    String topicName = "test_topic";
    String apiKey = System.getenv("CONFLUENT_API_KEY");
    String apiSecret = System.getenv("CONFLUENT_API_SECRET");
    String bootstrapServers = System.getenv("CONFLUENT_SERVERS");

    Properties properties = new Properties();
    properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.setProperty(
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.setProperty(
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
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

    String avroSchema =
        "{ "
            + "\"type\": \"record\", "
            + "\"name\": \"Person\", "
            + "\"fields\": ["
            + "   {\"name\": \"name\", \"type\": \"string\"}, "
            + "   {\"name\": \"age\", \"type\": \"int\"}, "
            + "   {\"name\": \"address\", \"type\": {"
            + "       \"type\": \"record\","
            + "       \"name\": \"Address\","
            + "       \"fields\": ["
            + "           {\"name\": \"city\", \"type\": \"string\"},"
            + "           {\"name\": \"zipCode\", \"type\": \"string\"}"
            + "       ]"
            + "   }}"
            + " ]}";

    // Avro record
    Schema schema = new Schema.Parser().parse(avroSchema);

    Producer<String, GenericRecord> producer =
        new org.apache.kafka.clients.producer.KafkaProducer<>(properties);

    try {
      for (int i = 1; i <= 20; i++) {
        GenericRecord avroRecord = new GenericData.Record(schema);

        // Set values for the fields
        avroRecord.put("name", "John Doe");
        avroRecord.put("age", 25);

        // Create a nested record for the "address" field
        GenericData.Record addressRecord =
            new GenericData.Record(schema.getField("address").schema());
        addressRecord.put("city", "Anytown");
        addressRecord.put("zipCode", "12345");
        avroRecord.put("address", addressRecord);

        Schema parsedSchema = avroRecord.getSchema();
        ProducerRecord<String, GenericRecord> producerRecord =
            new ProducerRecord<>(topicName, avroRecord);
        producer.send(producerRecord);
        Thread.sleep(1500);
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      producer.close();
    }
  }
}
