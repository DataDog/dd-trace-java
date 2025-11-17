package datadog.trace.instrumentation.confluentschemaregistry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Instrumenter.MethodTransformer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

/**
 * Instruments Confluent Schema Registry deserializers (Avro, Protobuf, and JSON) to capture
 * deserialization operations.
 */
public class KafkaDeserializerInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.confluent.kafka.serializers.KafkaAvroDeserializer",
      "io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer",
      "io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument deserialize(String topic, Headers headers, byte[] data)
    // The 2-arg version calls this one, so we only need to instrument this to avoid duplicates
    transformer.applyAdvice(
        isMethod()
            .and(named("deserialize"))
            .and(isPublic())
            .and(takesArguments(3))
            .and(takesArgument(0, String.class))
            .and(takesArgument(2, byte[].class)),
        getClass().getName() + "$DeserializeAdvice");
  }

  public static class DeserializeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This org.apache.kafka.common.serialization.Deserializer deserializer,
        @Advice.Argument(0) String topic,
        @Advice.Argument(2) byte[] data,
        @Advice.Return Object result,
        @Advice.Thrown Throwable throwable) {

      // Get isKey from the deserializer object
      boolean isKey = false;
      if (deserializer instanceof io.confluent.kafka.serializers.KafkaAvroDeserializer) {
        isKey = ((io.confluent.kafka.serializers.KafkaAvroDeserializer) deserializer).isKey();
      } else if (deserializer
          instanceof io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer) {
        isKey =
            ((io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer) deserializer)
                .isKey();
      } else if (deserializer
          instanceof io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer) {
        isKey =
            ((io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer) deserializer)
                .isKey();
      }

      boolean isError = throwable != null;
      int schemaId = -1;

      // Extract schema ID from the input bytes if successful
      if (!isError && data != null && data.length >= 5 && data[0] == 0) {
        try {
          // Confluent wire format: [magic_byte][4-byte schema id][data]
          schemaId =
              ((data[1] & 0xFF) << 24)
                  | ((data[2] & 0xFF) << 16)
                  | ((data[3] & 0xFF) << 8)
                  | (data[4] & 0xFF);
        } catch (Throwable ignored) {
          // If extraction fails, keep schemaId as -1
        }
      }

      // Record the schema registry usage
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .setSchemaRegistryUsage(topic, null, schemaId, isError, isKey);
    }
  }
}

