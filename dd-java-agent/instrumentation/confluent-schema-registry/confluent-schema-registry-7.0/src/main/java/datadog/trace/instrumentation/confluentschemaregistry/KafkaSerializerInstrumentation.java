package datadog.trace.instrumentation.confluentschemaregistry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Instrumenter.MethodTransformer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

/**
 * Instruments Confluent Schema Registry serializers (Avro, Protobuf, and JSON) to capture
 * serialization operations.
 */
public class KafkaSerializerInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.confluent.kafka.serializers.KafkaAvroSerializer",
      "io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializer",
      "io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument both serialize(String topic, Object data)
    // and serialize(String topic, Headers headers, Object data) for Kafka 2.1+
    transformer.applyAdvice(
        isMethod()
            .and(named("serialize"))
            .and(isPublic())
            .and(takesArgument(0, String.class))
            .and(returns(byte[].class)),
        getClass().getName() + "$SerializeAdvice");
  }

  public static class SerializeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This org.apache.kafka.common.serialization.Serializer serializer,
        @Advice.Argument(0) String topic,
        @Advice.Return byte[] result,
        @Advice.Thrown Throwable throwable) {

      // Get isKey from the serializer object
      boolean isKey = false;
      if (serializer instanceof io.confluent.kafka.serializers.KafkaAvroSerializer) {
        isKey = ((io.confluent.kafka.serializers.KafkaAvroSerializer) serializer).isKey();
      } else if (serializer
          instanceof io.confluent.kafka.serializer KafkaJsonSchemaSerializer) {
        isKey =
            ((io.confluent.kafka.serializers.KafkaJsonSchemaSerializer) serializer).isKey();
      } else if (serializer
          instanceof io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer) {
        isKey =
            ((io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer) serializer).isKey();
      }

      boolean isError = throwable != null;
      int schemaId = -1;

      // Extract schema ID from the serialized bytes if successful
      if (!isError && result != null && result.length >= 5 && result[0] == 0) {
        try {
          // Confluent wire format: [magic_byte][4-byte schema id][data]
          schemaId =
              ((result[1] & 0xFF) << 24)
                  | ((result[2] & 0xFF) << 16)
                  | ((result[3] & 0xFF) << 8)
                  | (result[4] & 0xFF);
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

