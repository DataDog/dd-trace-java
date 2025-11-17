import datadog.trace.agent.test.InstrumentationSpecification
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.serializers.KafkaAvroSerializer
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import spock.lang.Shared

import java.util.concurrent.TimeUnit

/**
 * Tests that Schema Registry usage is tracked in Data Streams Monitoring
 * for both serialization and deserialization operations.
 */
class ConfluentSchemaRegistryDataStreamsTest extends InstrumentationSpecification {
  @Shared
  SchemaRegistryClient schemaRegistryClient

  @Shared
  Schema testSchema

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  @Override
  protected long dataStreamsBucketDuration() {
    return TimeUnit.SECONDS.toNanos(1)
  }

  void setup() {
    schemaRegistryClient = new MockSchemaRegistryClient()
    testSchema = new Schema.Parser().parse("""
      {
        "type": "record",
        "name": "TestRecord",
        "fields": [
          {"name": "field1", "type": "string"},
          {"name": "field2", "type": "int"}
        ]
      }
    """)
  }

  def "test schema registry tracks both serialize and deserialize operations"() {
    setup:
    def topicName = "test-topic"
    def testClusterId = "test-cluster"
    def config = [
      "schema.registry.url": "mock://test-url",
      "auto.register.schemas": "true"
    ]

    // Create serializer and deserializer
    def serializer = new KafkaAvroSerializer(schemaRegistryClient)
    serializer.configure(config, false) // false = value serializer

    def deserializer = new KafkaAvroDeserializer(schemaRegistryClient)
    deserializer.configure(config, false) // false = value deserializer

    // Create a test record
    GenericRecord record = new GenericData.Record(testSchema)
    record.put("field1", "test-value")
    record.put("field2", 42)

    when: "we produce a message (serialize)"
    datadog.trace.instrumentation.confluentschemaregistry.ClusterIdHolder.set(testClusterId)
    byte[] serialized = serializer.serialize(topicName, record)

    and: "we consume the message (deserialize)"
    datadog.trace.instrumentation.confluentschemaregistry.ClusterIdHolder.set(testClusterId)
    def deserialized = deserializer.deserialize(topicName, serialized)

    and: "we wait for DSM to flush"
    Thread.sleep(1200) // Wait for bucket duration + buffer
    TEST_DATA_STREAMS_MONITORING.report()
    TEST_DATA_STREAMS_WRITER.waitForSchemaRegistryUsages(2, TimeUnit.SECONDS.toMillis(5))

    then: "the message was serialized and deserialized successfully"
    serialized != null
    serialized.length > 0
    deserialized != null
    deserialized.get("field1").toString() == "test-value"
    deserialized.get("field2") == 42

    and: "two schema registry usages were tracked"
    def usages = TEST_DATA_STREAMS_WRITER.schemaRegistryUsages
    usages.size() >= 2

    and: "one is a serialize operation"
    def serializeUsage = usages.find { u ->
      u.topic == topicName && u.operation == "serialize"
    }
    serializeUsage != null
    serializeUsage.schemaId > 0  // Valid schema ID
    serializeUsage.isSuccess() == true
    serializeUsage.isKey() == false
    serializeUsage.clusterId == testClusterId

    and: "one is a deserialize operation"
    def deserializeUsage = usages.find { u ->
      u.topic == topicName && u.operation == "deserialize"
    }
    deserializeUsage != null
    deserializeUsage.schemaId > 0  // Valid schema ID
    deserializeUsage.isSuccess() == true
    deserializeUsage.isKey() == false
    deserializeUsage.clusterId == testClusterId

    and: "both operations used the same schema ID"
    serializeUsage.schemaId == deserializeUsage.schemaId

    cleanup:
    serializer.close()
    deserializer.close()
    datadog.trace.instrumentation.confluentschemaregistry.ClusterIdHolder.clear()
  }
}
