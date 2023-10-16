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
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;

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
        "org.apache.kafka.clients.consumer.ConsumerRecords", KafkaConsumerInfo.class.getName());
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
      packageName + ".KafkaConsumerInfo",
      packageName + ".KafkaConsumerInstrumentation$Helper"
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
        Pair<String, String> data =
            KafkaConsumerInstrumentationHelper.extractGroupAndClusterId(
                records,
                InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class),
                InstrumentationContext.get(Metadata.class, String.class));
        iterable =
            new TracingIterable(
                iterable, KAFKA_CONSUME, CONSUMER_DECORATE, data.getLeft(), data.getRight());
      }
    }
  }

  public static class ListAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(
        @Advice.Return(readOnly = false) List<ConsumerRecord<?, ?>> iterable,
        @Advice.This ConsumerRecords records) {
      if (iterable != null) {
        Pair<String, String> data =
            KafkaConsumerInstrumentationHelper.extractGroupAndClusterId(
                records,
                InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class),
                InstrumentationContext.get(Metadata.class, String.class));
        iterable =
            new TracingList(
                iterable, KAFKA_CONSUME, CONSUMER_DECORATE, data.getLeft(), data.getRight());
      }
    }
  }

  public static class IteratorAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(
        @Advice.Return(readOnly = false) Iterator<ConsumerRecord<?, ?>> iterator,
        @Advice.This ConsumerRecords records) {
      if (iterator != null) {
        Pair<String, String> data =
            KafkaConsumerInstrumentationHelper.extractGroupAndClusterId(
                records,
                InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class),
                InstrumentationContext.get(Metadata.class, String.class));
        iterator =
            new TracingIterator(
                iterator, KAFKA_CONSUME, CONSUMER_DECORATE, data.getLeft(), data.getRight());
      }
    }
  }
}
