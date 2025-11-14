package datadog.trace.instrumentation.confluentschemaregistry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Instrumenter.MethodTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments AbstractKafkaSchemaSerDe (base class for Avro, Protobuf, and JSON deserializers) to
 * capture deserialization operations.
 */
public class KafkaAvroDeserializerInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "io.confluent.kafka.serializers.AbstractKafkaSchemaSerDe";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named("io.confluent.kafka.serializers.AbstractKafkaSchemaSerDe")
        .or(named("io.confluent.kafka.serializers.AbstractKafkaAvroDeserializer"))
        .or(named("io.confluent.kafka.serializers.KafkaAvroDeserializer"))
        .or(named("io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument deserialize(String topic, byte[] data)
    transformer.applyAdvice(
        isMethod()
            .and(named("deserialize"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, byte[].class)),
        getClass().getName() + "$DeserializeAdvice");

    // Instrument deserialize(String topic, Headers headers, byte[] data) for Kafka 2.1+
    transformer.applyAdvice(
        isMethod()
            .and(named("deserialize"))
            .and(isPublic())
            .and(takesArguments(3))
            .and(takesArgument(0, String.class))
            .and(takesArgument(2, byte[].class)),
        getClass().getName() + "$DeserializeWithHeadersAdvice");
  }

  public static class DeserializeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This Object deserializer,
        @Advice.Argument(0) String topic,
        @Advice.Argument(1) byte[] data) {
      // Set the topic in context
      SchemaRegistryContext.setTopic(topic);

      // Determine if this is a key or value deserializer
      String className = deserializer.getClass().getSimpleName();
      boolean isKey =
          className.contains("Key") || deserializer.getClass().getName().contains("Key");
      SchemaRegistryContext.setIsKey(isKey);

      // Extract schema ID from the data if present (Confluent wire format)
      if (data != null && data.length >= 5) {
        // Confluent wire format: [magic_byte][4-byte schema id][data]
        if (data[0] == 0) {
          int schemaId =
              ((data[1] & 0xFF) << 24)
                  | ((data[2] & 0xFF) << 16)
                  | ((data[3] & 0xFF) << 8)
                  | (data[4] & 0xFF);

          if (isKey) {
            SchemaRegistryContext.setKeySchemaId(schemaId);
          } else {
            SchemaRegistryContext.setValueSchemaId(schemaId);
          }
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Object deserializer,
        @Advice.Argument(0) String topic,
        @Advice.Return Object result,
        @Advice.Thrown Throwable throwable) {

      Boolean isKey = SchemaRegistryContext.getIsKey();
      Integer keySchemaId = SchemaRegistryContext.getKeySchemaId();
      Integer valueSchemaId = SchemaRegistryContext.getValueSchemaId();

      if (throwable != null) {
        SchemaRegistryMetrics.recordDeserializationFailure(
            topic, throwable.getMessage(), isKey != null ? isKey : false);
      } else if (result != null) {
        // Successful deserialization
        SchemaRegistryMetrics.recordDeserialization(
            topic, keySchemaId, valueSchemaId, isKey != null ? isKey : false);
      }

      // Clear context after deserialization
      SchemaRegistryContext.clear();
    }
  }

  public static class DeserializeWithHeadersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This Object deserializer,
        @Advice.Argument(0) String topic,
        @Advice.Argument(2) byte[] data) {
      // Set the topic in context
      SchemaRegistryContext.setTopic(topic);

      // Determine if this is a key or value deserializer
      String className = deserializer.getClass().getSimpleName();
      boolean isKey =
          className.contains("Key") || deserializer.getClass().getName().contains("Key");
      SchemaRegistryContext.setIsKey(isKey);

      // Extract schema ID from the data if present (Confluent wire format)
      if (data != null && data.length >= 5) {
        // Confluent wire format: [magic_byte][4-byte schema id][data]
        if (data[0] == 0) {
          int schemaId =
              ((data[1] & 0xFF) << 24)
                  | ((data[2] & 0xFF) << 16)
                  | ((data[3] & 0xFF) << 8)
                  | (data[4] & 0xFF);

          if (isKey) {
            SchemaRegistryContext.setKeySchemaId(schemaId);
          } else {
            SchemaRegistryContext.setValueSchemaId(schemaId);
          }
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Object deserializer,
        @Advice.Argument(0) String topic,
        @Advice.Return Object result,
        @Advice.Thrown Throwable throwable) {

      Boolean isKey = SchemaRegistryContext.getIsKey();
      Integer keySchemaId = SchemaRegistryContext.getKeySchemaId();
      Integer valueSchemaId = SchemaRegistryContext.getValueSchemaId();

      if (throwable != null) {
        SchemaRegistryMetrics.recordDeserializationFailure(
            topic, throwable.getMessage(), isKey != null ? isKey : false);
      } else if (result != null) {
        // Successful deserialization
        SchemaRegistryMetrics.recordDeserialization(
            topic, keySchemaId, valueSchemaId, isKey != null ? isKey : false);
      }

      // Clear context after deserialization
      SchemaRegistryContext.clear();
    }
  }
}
