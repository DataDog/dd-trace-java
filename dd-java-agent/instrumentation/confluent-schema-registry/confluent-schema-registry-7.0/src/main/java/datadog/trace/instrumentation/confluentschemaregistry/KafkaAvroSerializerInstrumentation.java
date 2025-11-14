package datadog.trace.instrumentation.confluentschemaregistry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Instrumenter.MethodTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments AbstractKafkaSchemaSerDe (base class for Avro, Protobuf, and JSON serializers) to
 * capture serialization operations.
 */
public class KafkaAvroSerializerInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "io.confluent.kafka.serializers.AbstractKafkaSchemaSerDe";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named("io.confluent.kafka.serializers.AbstractKafkaSchemaSerDe")
        .or(named("io.confluent.kafka.serializers.AbstractKafkaAvroSerializer"))
        .or(named("io.confluent.kafka.serializers.KafkaAvroSerializer"))
        .or(named("io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument serialize(String topic, Object data)
    transformer.applyAdvice(
        isMethod()
            .and(named("serialize"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, String.class))
            .and(returns(byte[].class)),
        getClass().getName() + "$SerializeAdvice");

    // Instrument serialize(String topic, Headers headers, Object data) for Kafka 2.1+
    transformer.applyAdvice(
        isMethod()
            .and(named("serialize"))
            .and(isPublic())
            .and(takesArguments(3))
            .and(takesArgument(0, String.class))
            .and(returns(byte[].class)),
        getClass().getName() + "$SerializeWithHeadersAdvice");
  }

  public static class SerializeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object serializer, @Advice.Argument(0) String topic) {
      // Set the topic in context so that the schema registry client can use it
      SchemaRegistryContext.setTopic(topic);

      // Determine if this is a key or value serializer
      String className = serializer.getClass().getSimpleName();
      boolean isKey = className.contains("Key") || serializer.getClass().getName().contains("Key");
      SchemaRegistryContext.setIsKey(isKey);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Object serializer,
        @Advice.Argument(0) String topic,
        @Advice.Return byte[] result,
        @Advice.Thrown Throwable throwable) {

      try {
        Boolean isKey = SchemaRegistryContext.getIsKey();

        if (throwable != null) {
          SchemaRegistryMetrics.recordSerializationFailure(
              topic, throwable.getMessage(), isKey != null ? isKey : false);
        } else if (result != null) {
          // Extract schema ID from the serialized bytes (Confluent wire format)
          Integer schemaId = null;
          try {
            // Confluent wire format: [magic_byte][4-byte schema id][data]
            if (result.length >= 5 && result[0] == 0) {
              schemaId =
                  ((result[1] & 0xFF) << 24)
                      | ((result[2] & 0xFF) << 16)
                      | ((result[3] & 0xFF) << 8)
                      | (result[4] & 0xFF);
            }
          } catch (Throwable ignored) {
            // Suppress any errors in schema ID extraction
          }

          // Store in context for correlation
          if (isKey != null && isKey) {
            SchemaRegistryContext.setKeySchemaId(schemaId);
          } else {
            SchemaRegistryContext.setValueSchemaId(schemaId);
          }

          // Get both schema IDs for logging
          Integer keySchemaId = SchemaRegistryContext.getKeySchemaId();
          Integer valueSchemaId = SchemaRegistryContext.getValueSchemaId();

          // Successful serialization
          SchemaRegistryMetrics.recordSerialization(
              topic, keySchemaId, valueSchemaId, isKey != null ? isKey : false);
        }
      } catch (Throwable t) {
        // Don't let instrumentation errors break the application
        // but try to log them if possible
        try {
          SchemaRegistryMetrics.recordSerializationFailure(topic, "Instrumentation error", false);
        } catch (Throwable ignored) {
          // Really suppress everything
        }
      } finally {
        // Clear context after serialization
        SchemaRegistryContext.clear();
      }
    }
  }

  public static class SerializeWithHeadersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object serializer, @Advice.Argument(0) String topic) {
      // Set the topic in context so that the schema registry client can use it
      SchemaRegistryContext.setTopic(topic);

      // Determine if this is a key or value serializer
      String className = serializer.getClass().getSimpleName();
      boolean isKey = className.contains("Key") || serializer.getClass().getName().contains("Key");
      SchemaRegistryContext.setIsKey(isKey);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Object serializer,
        @Advice.Argument(0) String topic,
        @Advice.Return byte[] result,
        @Advice.Thrown Throwable throwable) {

      try {
        Boolean isKey = SchemaRegistryContext.getIsKey();

        if (throwable != null) {
          SchemaRegistryMetrics.recordSerializationFailure(
              topic, throwable.getMessage(), isKey != null ? isKey : false);
        } else if (result != null) {
          // Extract schema ID from the serialized bytes (Confluent wire format)
          Integer schemaId = null;
          try {
            // Confluent wire format: [magic_byte][4-byte schema id][data]
            if (result.length >= 5 && result[0] == 0) {
              schemaId =
                  ((result[1] & 0xFF) << 24)
                      | ((result[2] & 0xFF) << 16)
                      | ((result[3] & 0xFF) << 8)
                      | (result[4] & 0xFF);
            }
          } catch (Throwable ignored) {
            // Suppress any errors in schema ID extraction
          }

          // Store in context for correlation
          if (isKey != null && isKey) {
            SchemaRegistryContext.setKeySchemaId(schemaId);
          } else {
            SchemaRegistryContext.setValueSchemaId(schemaId);
          }

          // Get both schema IDs for logging
          Integer keySchemaId = SchemaRegistryContext.getKeySchemaId();
          Integer valueSchemaId = SchemaRegistryContext.getValueSchemaId();

          // Successful serialization
          SchemaRegistryMetrics.recordSerialization(
              topic, keySchemaId, valueSchemaId, isKey != null ? isKey : false);
        }
      } catch (Throwable t) {
        // Don't let instrumentation errors break the application
        // but try to log them if possible
        try {
          SchemaRegistryMetrics.recordSerializationFailure(topic, "Instrumentation error", false);
        } catch (Throwable ignored) {
          // Really suppress everything
        }
      } finally {
        // Clear context after serialization
        SchemaRegistryContext.clear();
      }
    }
  }
}
