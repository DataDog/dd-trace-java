package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashMap;
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
@AutoService(Instrumenter.class)
public final class KafkaConsumerInfoInstrumentation extends Instrumenter.Tracing
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
      packageName + ".KafkaDecorator",
      packageName + ".KafkaConsumerInfo",
      packageName + ".KafkaConsumerInfo$Builder",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor()
            .and(takesArgument(0, named("org.apache.kafka.clients.consumer.ConsumerConfig")))
            .and(takesArgument(1, named("org.apache.kafka.common.serialization.Deserializer")))
            .and(takesArgument(2, named("org.apache.kafka.common.serialization.Deserializer"))),
        KafkaConsumerInfoInstrumentation.class.getName() + "$ConstructorAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("poll"))
            .and(takesArguments(1))
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
      KafkaConsumerInfo.Builder metadataBuilder = new KafkaConsumerInfo.Builder();
      metadataBuilder = metadataBuilder.clientMetadata(metadata);

      String consumerGroup = consumerConfig.getString(ConsumerConfig.GROUP_ID_CONFIG);
      if (consumerGroup != null && !consumerGroup.isEmpty()) {
        metadataBuilder = metadataBuilder.consumerGroup(consumerGroup);
      }

      KafkaConsumerInfo kafkaConsumerInfo = metadataBuilder.build();
      InstrumentationContext.get(KafkaConsumer.class, KafkaConsumerInfo.class)
          .put(consumer, kafkaConsumerInfo);
      InstrumentationContext.get(ConsumerCoordinator.class, KafkaConsumerInfo.class)
          .put(coordinator, kafkaConsumerInfo);
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
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.This KafkaConsumer consumer, @Advice.Return ConsumerRecords records) {
      KafkaConsumerInfo kafkaConsumerInfo =
          InstrumentationContext.get(KafkaConsumer.class, KafkaConsumerInfo.class).get(consumer);
      if (kafkaConsumerInfo != null) {
        InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class)
            .put(records, kafkaConsumerInfo);
      }
    }
  }
}
