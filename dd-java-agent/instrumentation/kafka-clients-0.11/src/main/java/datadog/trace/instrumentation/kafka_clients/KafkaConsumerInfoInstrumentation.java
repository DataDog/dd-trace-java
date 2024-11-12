package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.KAFKA_RECORDS_COUNT;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_POLL;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
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
    implements Instrumenter.ForSingleType {

  public KafkaConsumerInfoInstrumentation() {
    super("kafka");
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
        KafkaConsumerInfoInstrumentation.class.getName() + "$ConstructorAdvice");

    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(0, Map.class))
            .and(takesArgument(1, named("org.apache.kafka.common.serialization.Deserializer")))
            .and(takesArgument(2, named("org.apache.kafka.common.serialization.Deserializer"))),
        KafkaConsumerInfoInstrumentation.class.getName() + "$SecondConstructorAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("poll"))
            .and(takesArguments(1))
            .and(takesArgument(0, long.class))
            .and(returns(named("org.apache.kafka.clients.consumer.ConsumerRecords"))),
        KafkaConsumerInfoInstrumentation.class.getName() + "$RecordsAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("poll"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("java.time.Duration")))
            .and(returns(named("org.apache.kafka.clients.consumer.ConsumerRecords"))),
        KafkaConsumerInfoInstrumentation.class.getName() + "$RecordsAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.This KafkaConsumer consumer,
        @Advice.FieldValue("metadata") Metadata metadata,
        @Advice.FieldValue("coordinator") ConsumerCoordinator coordinator,
        @Advice.Argument(0) ConsumerConfig consumerConfig) {
      System.out.println("constructor advice!!!");
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

  public static class SecondConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.This KafkaConsumer consumer,
        @Advice.FieldValue("metadata") Metadata metadata,
        @Advice.FieldValue("coordinator") ConsumerCoordinator coordinator,
        @Advice.Argument(0) Map<String, Object> consumerConfig) {
      System.out.println("new constructor advice!!!");
      Object groupID = consumerConfig.get("group.id");
      String consumerGroup = groupID instanceof String ? (String) groupID : null;
      String normalizedConsumerGroup =
          consumerGroup != null && !consumerGroup.isEmpty() ? consumerGroup : null;
      System.out.println("consume group " + normalizedConsumerGroup);

      String bootstrapServers = null;
      Object bootstrapServersObj = consumerConfig.get("bootstrap.servers");
      if (bootstrapServersObj instanceof String) {
        bootstrapServers = (String) bootstrapServersObj;
        System.out.println("bootstrap servers " + bootstrapServers);
      } else {
        System.out.println("it s not a string");
      }
      // if (bootstrapServersList != null && !bootstrapServersList.isEmpty()) {
      //   bootstrapServers = String.join(",", bootstrapServersList);
      // }
      // Object bootstrapServersObj = consumerConfig.get("bootstrap.servers");
      // if (bootstrapServersObj instanceof List) {
      //   List<?> tempList = (List<?>) bootstrapServersObj;

      //   // Verify each element is a String
      //   if (!tempList.isEmpty() && tempList.stream().allMatch(element -> element instanceof String)) {
      //     bootstrapServers = String.join(",", (List<String>) tempList);
      //   }
      // }
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
    public static AgentScope onEnter() {
      boolean dataStreamsEnabled;
      if (activeSpan() != null) {
        dataStreamsEnabled = activeSpan().traceConfig().isDataStreamsEnabled();
      } else {
        dataStreamsEnabled = Config.get().isDataStreamsEnabled();
      }
      if (dataStreamsEnabled) {
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
      System.out.println("polling!");
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
