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
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.internals.ConsumerCoordinator;
import org.apache.kafka.clients.consumer.internals.ConsumerMetadata;

/** This instrumentation saves the consumer group in the context store for later use. */
@AutoService(Instrumenter.class)
public final class KafkaConsumerMetadataInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public KafkaConsumerMetadataInstrumentation() {
    super("kafka");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("org.apache.kafka.clients.Metadata", "java.lang.String");
    contextStores.put(
        "org.apache.kafka.clients.consumer.ConsumerRecords", KafkaConsumerMetadata.class.getName());
    contextStores.put(
        "org.apache.kafka.clients.consumer.internals.ConsumerCoordinator", "java.lang.String");
    contextStores.put(
        "org.apache.kafka.clients.consumer.KafkaConsumer", KafkaConsumerMetadata.class.getName());
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
      packageName + ".KafkaConsumerMetadata",
      packageName + ".KafkaConsumerMetadata$Builder",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor()
            .and(takesArgument(0, named("org.apache.kafka.clients.consumer.ConsumerConfig")))
            .and(takesArgument(1, named("org.apache.kafka.common.serialization.Deserializer")))
            .and(takesArgument(2, named("org.apache.kafka.common.serialization.Deserializer"))),
        KafkaConsumerMetadataInstrumentation.class.getName() + "$ConstructorAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("poll"))
            .and(takesArguments(1))
            .and(returns(named("org.apache.kafka.clients.consumer.ConsumerRecords"))),
        KafkaConsumerMetadataInstrumentation.class.getName() + "$RecordsAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.This KafkaConsumer consumer,
        @Advice.FieldValue("metadata") ConsumerMetadata metadata,
        @Advice.FieldValue("coordinator") ConsumerCoordinator coordinator,
        @Advice.Argument(0) ConsumerConfig consumerConfig) {
      KafkaConsumerMetadata.Builder metadataBuilder = new KafkaConsumerMetadata.Builder();
      metadataBuilder.consumerMetadata(metadata);

      String consumerGroup = consumerConfig.getString(ConsumerConfig.GROUP_ID_CONFIG);
      if (consumerGroup != null && !consumerGroup.isEmpty()) {
        metadataBuilder.consumerGroup(consumerGroup);
        InstrumentationContext.get(ConsumerCoordinator.class, String.class)
            .put(coordinator, consumerGroup);
      }

      KafkaConsumerMetadata kafkaConsumerMetadata = metadataBuilder.build();
      InstrumentationContext.get(KafkaConsumer.class, KafkaConsumerMetadata.class)
          .put(consumer, kafkaConsumerMetadata);
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
      KafkaConsumerMetadata kafkaConsumerMetadata =
          InstrumentationContext.get(KafkaConsumer.class, KafkaConsumerMetadata.class)
              .get(consumer);
      if (kafkaConsumerMetadata != null) {
        InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerMetadata.class)
            .put(records, kafkaConsumerMetadata);
      }
    }
  }
}
