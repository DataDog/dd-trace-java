package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_CONSUME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.internals.ConsumerMetadata;

@AutoService(Instrumenter.class)
public final class KafkaConsumerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public KafkaConsumerInstrumentation() {
    super("kafka");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("org.apache.kafka.clients.Metadata", "java.lang.String");
    contextStores.put(
        "org.apache.kafka.clients.consumer.ConsumerRecords", KafkaConsumerMetadata.class.getName());
    return contextStores;
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.consumer.ConsumerRecords";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KafkaDecorator",
      packageName + ".TextMapExtractAdapter",
      packageName + ".TracingIterableDelegator",
      packageName + ".TracingIterable",
      packageName + ".TracingIterator",
      packageName + ".TracingList",
      packageName + ".TracingListIterator",
      packageName + ".Base64Decoder",
      packageName + ".KafkaConsumerMetadata"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("records"))
            .and(takesArgument(0, String.class))
            .and(returns(Iterable.class)),
        KafkaConsumerInstrumentation.class.getName() + "$IterableAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("records"))
            .and(takesArgument(0, named("org.apache.kafka.common.TopicPartition")))
            .and(returns(List.class)),
        KafkaConsumerInstrumentation.class.getName() + "$ListAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("iterator"))
            .and(takesArguments(0))
            .and(returns(Iterator.class)),
        KafkaConsumerInstrumentation.class.getName() + "$IteratorAdvice");
  }

  public static class IterableAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(
        @Advice.Return(readOnly = false) Iterable<ConsumerRecord<?, ?>> iterable,
        @Advice.This ConsumerRecords records) {
      if (iterable != null) {
        String group = null;
        String clusterId = null;
        KafkaConsumerMetadata kafkaConsumerMetadata =
            InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerMetadata.class)
                .get(records);
        if (kafkaConsumerMetadata != null) {
          group = kafkaConsumerMetadata.getConsumerGroup();
          ConsumerMetadata consumerMetadata = kafkaConsumerMetadata.getConsumerMetadata();
          if (consumerMetadata != null) {
            clusterId =
                InstrumentationContext.get(Metadata.class, String.class).get(consumerMetadata);
          }
        }
        System.out.println("[KAFKACONSUMERMETADATA] cluster ID: " + clusterId);
        iterable =
            new TracingIterable(iterable, KAFKA_CONSUME, CONSUMER_DECORATE, group, clusterId);
      }
    }
  }

  public static class ListAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(
        @Advice.Return(readOnly = false) List<ConsumerRecord<?, ?>> iterable,
        @Advice.This ConsumerRecords records) {
      if (iterable != null) {
        String group = null;
        String clusterId = null;
        KafkaConsumerMetadata kafkaConsumerMetadata =
            InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerMetadata.class)
                .get(records);
        if (kafkaConsumerMetadata != null) {
          group = kafkaConsumerMetadata.getConsumerGroup();
          ConsumerMetadata consumerMetadata = kafkaConsumerMetadata.getConsumerMetadata();
          if (consumerMetadata != null) {
            clusterId =
                InstrumentationContext.get(Metadata.class, String.class).get(consumerMetadata);
          }
        }
        System.out.println("[KAFKACONSUMERMETADATA] cluster ID: " + clusterId);
        iterable = new TracingList(iterable, KAFKA_CONSUME, CONSUMER_DECORATE, group, clusterId);
      }
    }
  }

  public static class IteratorAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(
        @Advice.Return(readOnly = false) Iterator<ConsumerRecord<?, ?>> iterator,
        @Advice.This ConsumerRecords records) {
      if (iterator != null) {
        String group = null;
        String clusterId = null;
        KafkaConsumerMetadata kafkaConsumerMetadata =
            InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerMetadata.class)
                .get(records);
        if (kafkaConsumerMetadata != null) {
          group = kafkaConsumerMetadata.getConsumerGroup();
          ConsumerMetadata consumerMetadata = kafkaConsumerMetadata.getConsumerMetadata();
          if (consumerMetadata != null) {
            clusterId =
                InstrumentationContext.get(Metadata.class, String.class).get(consumerMetadata);
          }
        }
        System.out.println("[KAFKACONSUMERMETADATA] cluster ID: " + clusterId);
        iterator =
            new TracingIterator(iterator, KAFKA_CONSUME, CONSUMER_DECORATE, group, clusterId);
      }
    }
  }
}
