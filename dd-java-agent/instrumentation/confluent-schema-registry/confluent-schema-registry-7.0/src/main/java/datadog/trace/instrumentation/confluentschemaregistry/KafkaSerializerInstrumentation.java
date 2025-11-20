package datadog.trace.instrumentation.confluentschemaregistry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
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
import org.apache.kafka.common.serialization.Serializer;

/**
 * Instruments Confluent Schema Registry serializers (Avro, Protobuf, and JSON) to capture
 * serialization operations.
 */
@AutoService(InstrumenterModule.class)
public class KafkaSerializerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public KafkaSerializerInstrumentation() {
    super("confluent-schema-registry", "kafka");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.confluent.kafka.serializers.KafkaAvroSerializer",
      "io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializer",
      "io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer"
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.kafka_common.ClusterIdHolder",
      packageName + ".SchemaIdExtractor"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("org.apache.kafka.common.serialization.Serializer", "java.lang.Boolean");
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

  public static class ConfigureAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This Serializer serializer, @Advice.Argument(1) boolean isKey) {
      // Store the isKey value in InstrumentationContext for later use
      InstrumentationContext.get(Serializer.class, Boolean.class).put(serializer, isKey);
    }
  }

  public static class SerializeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Serializer serializer,
        @Advice.Argument(0) String topic,
        @Advice.Return byte[] result,
        @Advice.Thrown Throwable throwable) {

      // Get isKey from InstrumentationContext (stored during configure)
      Boolean isKeyObj =
          InstrumentationContext.get(Serializer.class, Boolean.class).get(serializer);
      boolean isKey = isKeyObj != null && isKeyObj;

      // Get cluster ID from thread-local (set by Kafka producer instrumentation)
      String clusterId = ClusterIdHolder.get();

      boolean isSuccess = throwable == null;
      int schemaId = isSuccess ? SchemaIdExtractor.extractSchemaId(result) : -1;

      // Record the schema registry usage
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .reportSchemaRegistryUsage(topic, clusterId, schemaId, isSuccess, isKey, "serialize");
    }
  }
}
