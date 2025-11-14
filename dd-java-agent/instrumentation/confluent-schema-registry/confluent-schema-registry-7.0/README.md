# Confluent Schema Registry Instrumentation

This instrumentation module provides detailed observability for Confluent Schema Registry operations in Kafka applications.

## Features

This instrumentation captures:

### Producer Operations
- **Schema Registration**: Tracks when schemas are registered with the Schema Registry
  - Subject name
  - Schema ID assigned
  - Success/failure status
  - Compatibility check results
- **Serialization**: Logs every message serialization with:
  - Topic name
  - Key schema ID (if applicable)
  - Value schema ID
  - Success/failure status

### Consumer Operations  
- **Deserialization**: Tracks every message deserialization with:
  - Topic name
  - Key schema ID (if present in message)
  - Value schema ID (extracted from Confluent wire format)
  - Success/failure status

### Schema Registry Client Operations
- **Schema Registration** (`register()` method)
  - Successful registrations with schema ID
  - Compatibility failures with error details
- **Compatibility Checks** (`testCompatibility()` method)
  - Pass/fail status
  - Error messages for incompatible schemas
- **Schema Retrieval** (`getSchemaById()` method)
  - Schema ID lookups during deserialization

## Metrics Collected

The `SchemaRegistryMetrics` class tracks:

- `schemaRegistrationSuccess` - Count of successful schema registrations
- `schemaRegistrationFailure` - Count of failed schema registrations (compatibility issues)
- `schemaCompatibilitySuccess` - Count of successful compatibility checks
- `schemaCompatibilityFailure` - Count of failed compatibility checks
- `serializationSuccess` - Count of successful message serializations
- `serializationFailure` - Count of failed serializations
- `deserializationSuccess` - Count of successful message deserializations
- `deserializationFailure` - Count of failed deserializations

## Log Output Examples

### Successful Producer Operation
```
[Schema Registry] Schema registered successfully - Subject: myTopic-value, Schema ID: 123, Is Key: false, Topic: myTopic
[Schema Registry] Produce to topic 'myTopic', schema for key: none, schema for value: 123, serializing: VALUE
```

### Failed Schema Registration (Incompatibility)
```
[Schema Registry] Schema registration FAILED - Subject: myTopic-value, Is Key: false, Topic: myTopic, Error: Schema being registered is incompatible with an earlier schema
[Schema Registry] Schema compatibility check FAILED - Subject: myTopic-value, Error: Schema being registered is incompatible with an earlier schema
[Schema Registry] Serialization FAILED for topic 'myTopic', VALUE - Error: Schema being registered is incompatible with an earlier schema
```

### Consumer Operation
```
[Schema Registry] Retrieved schema from registry - Schema ID: 123, Type: Schema
[Schema Registry] Consume from topic 'myTopic', schema for key: none, schema for value: 123, deserializing: VALUE
```

## Supported Serialization Formats

- **Avro** (via `KafkaAvroSerializer`/`KafkaAvroDeserializer`)
- **Protobuf** (via `KafkaProtobufSerializer`/`KafkaProtobufDeserializer`)

## Implementation Details

### Instrumented Classes

1. **CachedSchemaRegistryClient** - The main Schema Registry client
   - `register(String subject, Schema schema)` - Schema registration
   - `testCompatibility(String subject, Schema schema)` - Compatibility testing
   - `getSchemaById(int id)` - Schema retrieval

2. **AbstractKafkaSchemaSerDe and subclasses** - Serializers
   - `serialize(String topic, Object data)` - Message serialization
   - `serialize(String topic, Headers headers, Object data)` - With headers (Kafka 2.1+)

3. **AbstractKafkaSchemaSerDe and subclasses** - Deserializers
   - `deserialize(String topic, byte[] data)` - Message deserialization  
   - `deserialize(String topic, Headers headers, byte[] data)` - With headers (Kafka 2.1+)

### Context Management

The `SchemaRegistryContext` class uses ThreadLocal storage to pass context between:
- Serializer → Schema Registry Client (for logging topic information)
- Deserializer → Schema Registry Client (for logging topic information)

This allows the instrumentation to correlate schema operations with the topics they're associated with.

## Usage

This instrumentation is automatically activated when:
1. Confluent Schema Registry client (version 7.0.0+) is present on the classpath
2. The Datadog Java agent is attached to the JVM

No configuration or code changes are required.

## Metrics Access

To access metrics programmatically:

```java
import datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryMetrics;

// Get current counts
long registrationFailures = SchemaRegistryMetrics.getSchemaRegistrationFailureCount();
long compatibilityFailures = SchemaRegistryMetrics.getSchemaCompatibilityFailureCount();
long serializationFailures = SchemaRegistryMetrics.getSerializationFailureCount();

// Print summary
SchemaRegistryMetrics.printSummary();
```

## Monitoring Schema Compatibility Issues

The primary use case for this instrumentation is to detect and monitor schema compatibility issues that cause production failures. By tracking `schemaRegistrationFailure` and `schemaCompatibilityFailure` metrics, you can:

1. **Alert on schema compatibility failures** before they impact production
2. **Track the rate of schema-related errors** per topic
3. **Identify problematic schema changes** that break compatibility
4. **Monitor serialization/deserialization failure rates** as a proxy for schema issues

## Future Enhancements

Potential additions:
- JSON Schema serializer support (currently excluded due to dependency issues)
- Schema evolution tracking
- Schema version diff logging
- Integration with Datadog APM for schema-related span tags
