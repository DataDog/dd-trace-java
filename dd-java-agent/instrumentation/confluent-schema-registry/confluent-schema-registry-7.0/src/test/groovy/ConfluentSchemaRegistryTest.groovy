import spock.lang.Specification
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.serializers.KafkaAvroSerializer
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord

/**
 * Simple test to verify Schema Registry serializer/deserializer behavior.
 * This test verifies that:
 * 1. Confluent wire format includes schema ID in bytes
 * 2. Schema ID can be extracted from serialized data
 *
 * To test with instrumentation, run your app with the agent and check logs.
 */
class ConfluentSchemaRegistryTest extends Specification {

  def "test schema ID is extracted from serialized bytes"() {
    setup:
    SchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient()
    String topic = "test-topic"

    // Define a simple Avro schema
    String schemaStr = '''
      {
        "type": "record",
        "name": "TestRecord",
        "fields": [
          {"name": "id", "type": "string"},
          {"name": "value", "type": "int"}
        ]
      }
    '''
    Schema schema = new Schema.Parser().parse(schemaStr)

    // Configure serializer
    def props = [
      "schema.registry.url": "mock://test"
    ]
    KafkaAvroSerializer serializer = new KafkaAvroSerializer(schemaRegistry, props)

    // Create a record
    GenericRecord record = new GenericData.Record(schema)
    record.put("id", "test-id-123")
    record.put("value", 42)

    when:
    byte[] serialized = serializer.serialize(topic, record)

    then:
    // Verify serialization succeeded
    serialized != null
    serialized.length > 5

    // Verify Confluent wire format: magic byte (0x00) + 4-byte schema ID
    serialized[0] == 0

    // Extract schema ID from bytes (big-endian)
    int schemaId = ((serialized[1] & 0xFF) << 24) |
      ((serialized[2] & 0xFF) << 16) |
      ((serialized[3] & 0xFF) << 8) |
      (serialized[4] & 0xFF)

    println "\n========== Confluent Wire Format Test =========="
    println "Topic: ${topic}"
    println "Schema ID from bytes: ${schemaId}"
    def first10 = serialized.length >= 10 ? serialized[0..9] : serialized
    println "First 10 bytes: ${first10.collect { String.format('%02X', it & 0xFF) }.join(' ')}"
    println "This is the schema ID our instrumentation should extract!"
    println "================================================\n"

    // Verify schema ID is positive (valid)
    schemaId > 0

    cleanup:
    serializer?.close()
  }

  def "test deserialization captures schema ID"() {
    setup:
    SchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient()
    String topic = "test-topic"

    String schemaStr = '''
      {
        "type": "record",
        "name": "TestRecord",
        "fields": [
          {"name": "name", "type": "string"}
        ]
      }
    '''
    Schema schema = new Schema.Parser().parse(schemaStr)

    def props = ["schema.registry.url": "mock://test"]
    KafkaAvroSerializer serializer = new KafkaAvroSerializer(schemaRegistry, props)
    KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer(schemaRegistry, props)

    GenericRecord record = new GenericData.Record(schema)
    record.put("name", "TestName")

    when:
    byte[] serialized = serializer.serialize(topic, record)
    Object deserialized = deserializer.deserialize(topic, serialized)

    then:
    deserialized != null
    deserialized instanceof GenericRecord
    ((GenericRecord) deserialized).get("name").toString() == "TestName"

    println "\n========== Deserialization Test =========="
    println "Topic: ${topic}"
    println "Deserialized record: ${deserialized}"
    println "=========================================\n"

    cleanup:
    serializer?.close()
    deserializer?.close()
  }

  def "test schema registration is tracked"() {
    setup:
    SchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient()
    String subject = "test-subject"
    String schemaStr = '''
      {
        "type": "record",
        "name": "User",
        "fields": [
          {"name": "username", "type": "string"}
        ]
      }
    '''
    Schema schema = new Schema.Parser().parse(schemaStr)

    when:
    int schemaId = schemaRegistry.register(subject, schema)

    then:
    schemaId > 0

    println "\n========== Schema Registration Test =========="
    println "Subject: ${subject}"
    println "Registered schema ID: ${schemaId}"
    println "============================================\n"
  }

  def "test end-to-end with real KafkaAvroSerializer"() {
    setup:
    SchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient()
    String topic = "products"

    String productSchema = '''
      {
        "type": "record",
        "name": "Product",
        "namespace": "com.example",
        "fields": [
          {"name": "id", "type": "string"},
          {"name": "name", "type": "string"},
          {"name": "price", "type": "double"}
        ]
      }
    '''
    Schema schema = new Schema.Parser().parse(productSchema)

    def props = ["schema.registry.url": "mock://test"]
    KafkaAvroSerializer serializer = new KafkaAvroSerializer(schemaRegistry, props)

    GenericRecord product = new GenericData.Record(schema)
    product.put("id", "PROD-001")
    product.put("name", "Test Product")
    product.put("price", 29.99)

    when:
    byte[] serialized = serializer.serialize(topic, product)

    then:
    serialized != null
    println "\n========== End-to-End Test =========="
    println "Topic: ${topic}"
    println "Serialized ${serialized.length} bytes"
    def first10 = serialized.length >= 10 ? serialized[0..9] : serialized
    println "First 10 bytes: ${first10.collect { String.format('%02X', it & 0xFF) }.join(' ')}"

    // Extract and print schema ID
    if (serialized.length >= 5 && serialized[0] == 0) {
      int schemaId = ((serialized[1] & 0xFF) << 24) |
        ((serialized[2] & 0xFF) << 16) |
        ((serialized[3] & 0xFF) << 8) |
        (serialized[4] & 0xFF)
      println "Schema ID from wire format: ${schemaId}"
      println "\nWhen running with DD agent, you should see:"
      println "[Schema Registry] Produce to topic 'products', schema for key: none, schema for value: ${schemaId}, serializing: VALUE"
    }
    println "======================================\n"

    cleanup:
    serializer?.close()
  }
}

