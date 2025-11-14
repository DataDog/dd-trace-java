package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.KAFKA_RECORDS_COUNT;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_POLL;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.internals.ConsumerCoordinator;

/**
 * This instrumentation saves additional information from the KafkaConsumer, such as consumer group
 * and cluster ID, in the context store for later use.
 */
@AutoService(InstrumenterModule.class)
public final class KafkaConsumerInfoInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public KafkaConsumerInfoInstrumentation() {
    super("kafka", "kafka-0.11");
  }

  @Override
  public String muzzleDirective() {
    return "before-3.8";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return not(hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy")); // < 3.8
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("org.apache.kafka.clients.Metadata", "java.lang.String");
    contextStores.put(
        "org.apache.kafka.clients.consumer.ConsumerRecords", KafkaConsumerInfo.class.getName());
    contextStores.put(
        "org.apache.kafka.clients.consumer.internals.ConsumerCoordinator",
        KafkaConsumerInfo.class.getName());
    contextStores.put(
        "org.apache.kafka.clients.consumer.KafkaConsumer", KafkaConsumerInfo.class.getName());
    return contextStores;
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.consumer.KafkaConsumer";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KafkaDecorator", packageName + ".KafkaConsumerInfo",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(0, named("org.apache.kafka.clients.consumer.ConsumerConfig")))
            .and(takesArgument(1, named("org.apache.kafka.common.serialization.Deserializer")))
            .and(takesArgument(2, named("org.apache.kafka.common.serialization.Deserializer"))),
        KafkaConsumerInfoInstrumentation.class.getName() + "$ConstructorAdviceNot27");

    // Note: On some Kafka versions, both constructors will be instrumented. This is OK as we will
    // override the context,
    // and the instrumentation will still work as expected.
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(0, Map.class))
            .and(takesArgument(1, named("org.apache.kafka.common.serialization.Deserializer")))
            .and(takesArgument(2, named("org.apache.kafka.common.serialization.Deserializer"))),
        KafkaConsumerInfoInstrumentation.class.getName() + "$ConstructorAdvice27");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("poll"))
            .and(takesArguments(1))
            .and(returns(named("org.apache.kafka.clients.consumer.ConsumerRecords"))),
        KafkaConsumerInfoInstrumentation.class.getName() + "$RecordsAdvice");
  }

  public static class ConstructorAdviceNot27 {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.This KafkaConsumer consumer,
        @Advice.FieldValue("metadata") Metadata metadata,
        @Advice.FieldValue("coordinator") ConsumerCoordinator coordinator,
        @Advice.Argument(0) ConsumerConfig consumerConfig) {
      String consumerGroup = consumerConfig.getString(ConsumerConfig.GROUP_ID_CONFIG);
      String normalizedConsumerGroup =
          consumerGroup != null && !consumerGroup.isEmpty() ? consumerGroup : null;

      List<String> bootstrapServersList =
          consumerConfig.getList(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
      String bootstrapServers = null;
      if (bootstrapServersList != null && !bootstrapServersList.isEmpty()) {
        bootstrapServers = String.join(",", bootstrapServersList);
      }

      KafkaConsumerInfo kafkaConsumerInfo;
      if (Config.get().isDataStreamsEnabled()) {
        kafkaConsumerInfo =
            new KafkaConsumerInfo(normalizedConsumerGroup, metadata, bootstrapServers);
      } else {
        kafkaConsumerInfo = new KafkaConsumerInfo(normalizedConsumerGroup, bootstrapServers);
      }

      if (kafkaConsumerInfo.getConsumerGroup() != null
          || kafkaConsumerInfo.getClientMetadata() != null) {
        InstrumentationContext.get(KafkaConsumer.class, KafkaConsumerInfo.class)
            .put(consumer, kafkaConsumerInfo);
        if (coordinator != null) {
          InstrumentationContext.get(ConsumerCoordinator.class, KafkaConsumerInfo.class)
              .put(coordinator, kafkaConsumerInfo);
        }
      }
    }

    public static void muzzleCheck(ConsumerRecord record) {
      // KafkaConsumerInstrumentation only applies for kafka versions with headers
      // Make an explicit call so KafkaConsumerGroupInstrumentation does the same
      record.headers();
    }
  }

  public static class ConstructorAdvice27 {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.This KafkaConsumer consumer,
        @Advice.FieldValue("metadata") Metadata metadata,
        @Advice.FieldValue("coordinator") ConsumerCoordinator coordinator,
        @Advice.Argument(0) Map<String, Object> consumerConfig) {
      Object groupID = consumerConfig.get(ConsumerConfig.GROUP_ID_CONFIG);
      String consumerGroup = groupID instanceof String ? (String) groupID : null;
      String normalizedConsumerGroup =
          consumerGroup != null && !consumerGroup.isEmpty() ? consumerGroup : null;

      String bootstrapServers = null;
      Object bootstrapServersObj = consumerConfig.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
      if (bootstrapServersObj instanceof String) {
        bootstrapServers = (String) bootstrapServersObj;
      }
      KafkaConsumerInfo kafkaConsumerInfo;
      if (Config.get().isDataStreamsEnabled()) {
        kafkaConsumerInfo =
            new KafkaConsumerInfo(normalizedConsumerGroup, metadata, bootstrapServers);
      } else {
        kafkaConsumerInfo = new KafkaConsumerInfo(normalizedConsumerGroup, bootstrapServers);
      }

      if (kafkaConsumerInfo.getConsumerGroup() != null
          || kafkaConsumerInfo.getClientMetadata() != null) {
        InstrumentationContext.get(KafkaConsumer.class, KafkaConsumerInfo.class)
            .put(consumer, kafkaConsumerInfo);
        if (coordinator != null) {
          InstrumentationContext.get(ConsumerCoordinator.class, KafkaConsumerInfo.class)
              .put(coordinator, kafkaConsumerInfo);
        }
      }
    }

    public static void muzzleCheck(ConsumerRecord record) {
      // KafkaConsumerInstrumentation only applies for kafka versions with headers
      // Make an explicit call so KafkaConsumerGroupInstrumentation does the same
      record.headers();
    }
  }

  /**
   * this method transfers the consumer group from the KafkaConsumer class key to the
   * ConsumerRecords key. This is necessary because in the poll method, we don't have access to the
   * KafkaConsumer class.
   */
  public static class RecordsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This KafkaConsumer consumer) {
      // Set cluster ID in SchemaRegistryContext before deserialization
      // Use reflection to avoid compile-time dependency on the schema registry module
      KafkaConsumerInfo kafkaConsumerInfo =
          InstrumentationContext.get(KafkaConsumer.class, KafkaConsumerInfo.class).get(consumer);
      System.out.println("[DEBUG Consumer] kafkaConsumerInfo: " + kafkaConsumerInfo);
      if (kafkaConsumerInfo != null) {
        // Extract cluster ID directly inline to avoid muzzle issues
        String clusterId = null;
        if (Config.get().isDataStreamsEnabled()) {
          Metadata consumerMetadata = kafkaConsumerInfo.getClientMetadata();
          System.out.println("[DEBUG Consumer] metadata: " + consumerMetadata);
          if (consumerMetadata != null) {
            clusterId =
                InstrumentationContext.get(Metadata.class, String.class).get(consumerMetadata);
          }
        }
        System.out.println("[DEBUG Consumer] Extracted clusterId: " + clusterId);
        if (clusterId != null) {
          try {
            Class<?> contextClass =
                Class.forName(
                    "datadog.trace.instrumentation.confluentschemaregistry.SchemaRegistryContext",
                    false,
                    consumer.getClass().getClassLoader());
            contextClass.getMethod("setClusterId", String.class).invoke(null, clusterId);
            System.out.println(
                "[DEBUG Consumer] Set clusterId in SchemaRegistryContext: " + clusterId);
          } catch (Throwable t) {
            // Ignore if SchemaRegistryContext is not available
            System.out.println("[DEBUG Consumer] Failed to set clusterId: " + t.getMessage());
          }
        }
      }

      if (traceConfig().isDataStreamsEnabled()) {
        final AgentSpan span = startSpan(KAFKA_POLL);
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.Enter final AgentScope scope,
        @Advice.This KafkaConsumer consumer,
        @Advice.Return ConsumerRecords records) {
      int recordsCount = 0;
      if (records != null) {
        KafkaConsumerInfo kafkaConsumerInfo =
            InstrumentationContext.get(KafkaConsumer.class, KafkaConsumerInfo.class).get(consumer);
        if (kafkaConsumerInfo != null) {
          InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class)
              .put(records, kafkaConsumerInfo);
        }
        recordsCount = records.count();
      }
      if (scope == null) {
        return;
      }
      AgentSpan span = scope.span();
      span.setTag(KAFKA_RECORDS_COUNT, recordsCount);
      span.finish();
      scope.close();
    }
  }
}
