package datadog.trace.instrumentation.confluentschemaregistry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Instrumenter.MethodTransformer;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.kafka_common.ClusterIdHolder;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * Instruments Confluent Schema Registry deserializers (Avro, Protobuf, and JSON) to capture
 * deserialization operations.
 */
@AutoService(InstrumenterModule.class)
public class KafkaDeserializerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public KafkaDeserializerInstrumentation() {
    super("confluent-schema-registry", "kafka");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.confluent.kafka.serializers.KafkaAvroDeserializer",
      "io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer",
      "io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer"
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.kafka_common.ClusterIdHolder"};
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("org.apache.kafka.common.serialization.Deserializer", "java.lang.Boolean");
    return contextStores;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument configure to capture isKey value
    transformer.applyAdvice(
        isMethod()
            .and(named("configure"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(1, boolean.class)),
        getClass().getName() + "$ConfigureAdvice");

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

  public static class ConfigureAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This Deserializer deserializer, @Advice.Argument(1) boolean isKey) {
      // Store the isKey value in InstrumentationContext for later use
      InstrumentationContext.get(Deserializer.class, Boolean.class).put(deserializer, isKey);
    }
  }

  public static class DeserializeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Deserializer deserializer,
        @Advice.Argument(0) String topic,
        @Advice.Argument(2) byte[] data,
        @Advice.Return Object result,
        @Advice.Thrown Throwable throwable) {

      // Get isKey from InstrumentationContext (stored during configure)
      Boolean isKeyObj =
          InstrumentationContext.get(Deserializer.class, Boolean.class).get(deserializer);
      boolean isKey = isKeyObj != null && isKeyObj;

      // Get cluster ID from thread-local (set by Kafka consumer instrumentation)
      String clusterId = ClusterIdHolder.get();

      boolean isSuccess = throwable == null;
      int schemaId = -1;

      // Extract schema ID from the input bytes if successful
      if (isSuccess && data != null && data.length >= 5 && data[0] == 0) {
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
          .reportSchemaRegistryUsage(topic, clusterId, schemaId, isSuccess, isKey, "deserialize");
    }
  }
}
