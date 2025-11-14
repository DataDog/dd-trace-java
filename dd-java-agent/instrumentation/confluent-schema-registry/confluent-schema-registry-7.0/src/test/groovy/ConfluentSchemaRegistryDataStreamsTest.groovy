import datadog.trace.agent.test.InstrumentationSpecification
import io.confluent.kafka.serializers.KafkaAvroSerializer
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.common.serialization.Serializer
import spock.lang.Shared

import java.util.concurrent.TimeUnit

/**
 * Tests that Schema Registry usage is tracked in Data Streams Monitoring.
 * Tests both successful and unsuccessful serialization/deserialization operations.
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

  def "test successful producer serialization tracks schema registry usage"() {
    setup:
    def topicName = "test-topic-producer"
    def testClusterId = "test-cluster-producer"
    def serializer = new KafkaAvroSerializer(schemaRegistryClient)
    def config = [
      "schema.registry.url": "mock://test-url",
      "auto.register.schemas": "true"
    ]
    serializer.configure(config, false) // false = value serializer

    // Create a test record
    GenericRecord record = new GenericData.Record(testSchema)
    record.put("field1", "test-value")
    record.put("field2", 42)

    when: "we serialize a message with cluster ID set"
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.setClusterId(testClusterId)
    byte[] serialized = serializer.serialize(topicName, record)

    and: "we wait for DSM to flush"
    Thread.sleep(1200) // Wait for bucket duration + buffer
    TEST_DATA_STREAMS_MONITORING.report()
    TEST_DATA_STREAMS_WRITER.waitForSchemaRegistryUsages(1, TimeUnit.SECONDS.toMillis(5))

    then: "the serialization was successful"
    serialized != null
    serialized.length > 0

    and: "schema registry usage was tracked"
    def usages = TEST_DATA_STREAMS_WRITER.schemaRegistryUsages
    usages.size() >= 1

    and: "the usage contains the correct information"
    def usage = usages.find { u -> u.topic == topicName }
    usage != null
    usage.schemaId > 0  // Valid schema ID
    usage.isSuccess() == true  // Successful operation
    usage.isKey() == false  // Value serializer
    usage.clusterId == testClusterId  // Cluster ID is included

    cleanup:
    serializer.close()
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.clearAll()
  }

  def "test successful producer with key and value serializers"() {
    setup:
    def topicName = "test-topic-key-value"
    def testClusterId = "test-cluster-key-value"
    def keySerializer = new KafkaAvroSerializer(schemaRegistryClient)
    def valueSerializer = new KafkaAvroSerializer(schemaRegistryClient)
    def config = [
      "schema.registry.url": "mock://test-url",
      "auto.register.schemas": "true"
    ]
    keySerializer.configure(config, true)  // true = key serializer
    valueSerializer.configure(config, false) // false = value serializer

    // Create test records
    GenericRecord keyRecord = new GenericData.Record(testSchema)
    keyRecord.put("field1", "key-value")
    keyRecord.put("field2", 1)

    GenericRecord valueRecord = new GenericData.Record(testSchema)
    valueRecord.put("field1", "value-value")
    valueRecord.put("field2", 2)

    when: "we serialize both key and value with cluster ID"
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.setClusterId(testClusterId)
    byte[] keyBytes = keySerializer.serialize(topicName, keyRecord)
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.setClusterId(testClusterId)
    byte[] valueBytes = valueSerializer.serialize(topicName, valueRecord)

    and: "we wait for DSM to flush"
    Thread.sleep(1200) // Wait for bucket duration + buffer
    TEST_DATA_STREAMS_MONITORING.report()
    TEST_DATA_STREAMS_WRITER.waitForSchemaRegistryUsages(2, TimeUnit.SECONDS.toMillis(5))

    then: "both serializations were successful"
    keyBytes != null && keyBytes.length > 0
    valueBytes != null && valueBytes.length > 0

    and: "both usages were tracked"
    def usages = TEST_DATA_STREAMS_WRITER.schemaRegistryUsages
    usages.size() >= 2

    and: "we have both key and value tracked"
    def keyUsage = usages.find { u -> u.isKey() && u.topic == topicName }
    def valueUsage = usages.find { u -> !u.isKey() && u.topic == topicName }

    keyUsage != null
    keyUsage.schemaId > 0
    keyUsage.isSuccess() == true
    keyUsage.clusterId == testClusterId

    valueUsage != null
    valueUsage.schemaId > 0
    valueUsage.isSuccess() == true
    valueUsage.clusterId == testClusterId

    cleanup:
    keySerializer.close()
    valueSerializer.close()
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.clearAll()
  }

  def "test serialization failure is tracked"() {
    setup:
    def topicName = "test-topic-failure"

    // Create a custom serializer that will fail
    Serializer failingSerializer = new KafkaAvroSerializer(schemaRegistryClient) {
        @Override
        byte[] serialize(String topic, Object data) {
          throw new RuntimeException("Intentional serialization failure")
        }
      }
    def config = [
      "schema.registry.url": "mock://test-url"
    ]
    failingSerializer.configure(config, false)

    GenericRecord record = new GenericData.Record(testSchema)
    record.put("field1", "test")
    record.put("field2", 123)

    when: "we try to serialize and it fails"
    try {
      failingSerializer.serialize(topicName, record)
    } catch (RuntimeException e) {
      // Expected
    }

    and: "we wait for DSM to flush"
    Thread.sleep(1200)
    TEST_DATA_STREAMS_MONITORING.report()
    TEST_DATA_STREAMS_WRITER.waitForSchemaRegistryUsages(1, TimeUnit.SECONDS.toMillis(5))

    then: "the failure was tracked"
    def usages = TEST_DATA_STREAMS_WRITER.schemaRegistryUsages
    def failureUsage = usages.find { u -> u.topic == topicName }

    failureUsage != null
    failureUsage.schemaId == -1  // Failure indicator
    failureUsage.isSuccess() == false

    cleanup:
    failingSerializer.close()
  }

  def "test schema IDs are correctly extracted from serialized messages"() {
    setup:
    def topicName = "test-topic-schema-id"
    def testClusterId = "test-cluster-schema-id"
    def serializer = new KafkaAvroSerializer(schemaRegistryClient)
    def config = [
      "schema.registry.url": "mock://test-url",
      "auto.register.schemas": "true"
    ]
    serializer.configure(config, false)

    // Create multiple records with the same schema
    def records = (1..3).collect { i ->
      GenericRecord record = new GenericData.Record(testSchema)
      record.put("field1", "value-$i")
      record.put("field2", i)
      record
    }

    when: "we serialize multiple messages with cluster ID"
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.setClusterId(testClusterId)
    def serializedMessages = records.collect { record ->
      serializer.serialize(topicName, record)
    }

    and: "we wait for DSM to flush"
    Thread.sleep(1200)
    TEST_DATA_STREAMS_MONITORING.report()
    TEST_DATA_STREAMS_WRITER.waitForSchemaRegistryUsages(3, TimeUnit.SECONDS.toMillis(5))

    then: "all messages were serialized"
    serializedMessages.every { m -> m != null && m.length > 0 }

    and: "all usages have the same schema ID (cached schema)"
    def usages = TEST_DATA_STREAMS_WRITER.schemaRegistryUsages
      .findAll { u -> u.topic == topicName }
    usages.size() >= 3

    def schemaIds = usages.collect { u -> u.schemaId }.unique()
    schemaIds.size() == 1  // All use the same schema ID
    schemaIds[0] > 0  // Valid schema ID

    and: "cluster ID is present"
    usages.every { u -> u.clusterId == testClusterId }

    cleanup:
    serializer.close()
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.clearAll()
  }

  def "test schema registry usage metrics are aggregated by topic"() {
    setup:
    def topic1 = "test-topic-1"
    def topic2 = "test-topic-2"
    def testClusterId = "test-cluster-multi-topic"
    def serializer = new KafkaAvroSerializer(schemaRegistryClient)
    def config = [
      "schema.registry.url": "mock://test-url",
      "auto.register.schemas": "true"
    ]
    serializer.configure(config, false)

    GenericRecord record = new GenericData.Record(testSchema)
    record.put("field1", "test")
    record.put("field2", 1)

    when: "we produce to multiple topics with cluster ID"
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.setClusterId(testClusterId)
    serializer.serialize(topic1, record)
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.setClusterId(testClusterId)
    serializer.serialize(topic2, record)
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.setClusterId(testClusterId)
    serializer.serialize(topic1, record) // Second message to topic1

    and: "we wait for DSM to flush"
    Thread.sleep(1200)
    TEST_DATA_STREAMS_MONITORING.report()
    TEST_DATA_STREAMS_WRITER.waitForSchemaRegistryUsages(3, TimeUnit.SECONDS.toMillis(5))

    then: "usages are tracked per topic"
    def usages = TEST_DATA_STREAMS_WRITER.schemaRegistryUsages
    def topic1Usages = usages.findAll { u -> u.topic == topic1 }
    def topic2Usages = usages.findAll { u -> u.topic == topic2 }

    topic1Usages.size() >= 2
    topic2Usages.size() >= 1

    and: "all are successful"
    usages.every { u -> u.isSuccess() }

    cleanup:
    serializer.close()
    datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext.clearAll()
  }
}

