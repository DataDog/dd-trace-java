# Confluent Schema Registry Instrumentation

This instrumentation module provides Data Streams Monitoring (DSM) support for Confluent Schema Registry operations in Kafka applications.

## Features

This instrumentation captures schema registry usage for both serialization and deserialization operations, reporting them to Datadog's Data Streams Monitoring.

### Producer Operations
- **Serialization**: Tracks message serialization with:
  - Topic name
  - Schema ID (extracted from serialized bytes)
  - Key vs value serialization
  - Success/failure status

### Consumer Operations  
- **Deserialization**: Tracks message deserialization with:
  - Topic name
  - Schema ID (extracted from Confluent wire format)
  - Key vs value deserialization
  - Success/failure status

## Supported Serialization Formats

- **Avro** (via `KafkaAvroSerializer`/`KafkaAvroDeserializer`)
- **JSON Schema** (via `KafkaJsonSchemaSerializer`/`KafkaJsonSchemaDeserializer`)
- **Protobuf** (via `KafkaProtobufSerializer`/`KafkaProtobufDeserializer`)

## Implementation Details

### Instrumented Classes

1. **KafkaAvroSerializer** - Avro message serialization
2. **KafkaJsonSchemaSerializer** - JSON Schema message serialization
3. **KafkaProtobufSerializer** - Protobuf message serialization
4. **KafkaAvroDeserializer** - Avro message deserialization
5. **KafkaJsonSchemaDeserializer** - JSON Schema message deserialization
6. **KafkaProtobufDeserializer** - Protobuf message deserialization

### Schema ID Extraction

Schema IDs are extracted directly from the Confluent wire format:
- **Format**: `[magic_byte][4-byte schema id][data]`
- The magic byte (0x00) indicates Confluent wire format
- Schema ID is a 4-byte big-endian integer

### Key vs Value Detection

The instrumentation determines whether a serializer/deserializer is for keys or values by calling the `isKey()` method available on all Confluent serializers/deserializers.

## Usage

This instrumentation is automatically activated when:
1. Confluent Schema Registry client (version 7.0.0+) is present on the classpath
2. The Datadog Java agent is attached to the JVM
3. Data Streams Monitoring is enabled

No configuration or code changes are required.

## Data Streams Monitoring Integration

Schema registry usage is reported directly to Datadog's Data Streams Monitoring via:

```java
AgentTracer.get()
    .getDataStreamsMonitoring()
    .setSchemaRegistryUsage(topic, clusterId, schemaId, isError, isKey);
```

This allows tracking of:
- Schema usage patterns across topics
- Schema registry operation success rates
- Key vs value schema usage
- Schema evolution over time
