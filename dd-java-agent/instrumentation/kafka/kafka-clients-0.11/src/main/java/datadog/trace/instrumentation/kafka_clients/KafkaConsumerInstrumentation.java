package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_CONSUME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;

@AutoService(InstrumenterModule.class)
public final class KafkaConsumerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public KafkaConsumerInstrumentation() {
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
    Map<String, String> contextStores = new HashMap<>(2);
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
      packageName + ".TextMapInjectAdapterInterface",
      packageName + ".KafkaConsumerInfo",
      packageName + ".KafkaConsumerInstrumentationHelper",
      packageName + ".KafkaDecorator",
      packageName + ".TextMapExtractAdapter",
      packageName + ".TracingIterableDelegator",
      packageName + ".TracingIterable",
      packageName + ".TracingIterator",
      packageName + ".TracingList",
      packageName + ".TracingListIterator",
      packageName + ".TextMapInjectAdapter",
      "datadog.trace.instrumentation.kafka_common.Utils",
      "datadog.trace.instrumentation.kafka_common.StreamingContext",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("records"))
            .and(takesArgument(0, String.class))
            .and(returns(Iterable.class)),
        KafkaConsumerInstrumentation.class.getName() + "$IterableAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("records"))
            .and(takesArgument(0, named("org.apache.kafka.common.TopicPartition")))
            .and(returns(List.class)),
        KafkaConsumerInstrumentation.class.getName() + "$ListAdvice");
    transformer.applyAdvice(
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
        KafkaConsumerInfo kafkaConsumerInfo =
            InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class).get(records);
        String group = KafkaConsumerInstrumentationHelper.extractGroup(kafkaConsumerInfo);
        String clusterId =
            KafkaConsumerInstrumentationHelper.extractClusterId(
                kafkaConsumerInfo, InstrumentationContext.get(Metadata.class, String.class));
        String bootstrapServers =
            KafkaConsumerInstrumentationHelper.extractBootstrapServers(kafkaConsumerInfo);
        iterable =
            new TracingIterable(
                iterable, KAFKA_CONSUME, CONSUMER_DECORATE, group, clusterId, bootstrapServers);
      }
    }
  }

  public static class ListAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(
        @Advice.Return(readOnly = false) List<ConsumerRecord<?, ?>> iterable,
        @Advice.This ConsumerRecords records) {
      if (iterable != null) {
        KafkaConsumerInfo kafkaConsumerInfo =
            InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class).get(records);
        String group = KafkaConsumerInstrumentationHelper.extractGroup(kafkaConsumerInfo);
        String clusterId =
            KafkaConsumerInstrumentationHelper.extractClusterId(
                kafkaConsumerInfo, InstrumentationContext.get(Metadata.class, String.class));
        String bootstrapServers =
            KafkaConsumerInstrumentationHelper.extractBootstrapServers(kafkaConsumerInfo);
        iterable =
            new TracingList(
                iterable, KAFKA_CONSUME, CONSUMER_DECORATE, group, clusterId, bootstrapServers);
      }
    }
  }

  public static class IteratorAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(
        @Advice.Return(readOnly = false) Iterator<ConsumerRecord<?, ?>> iterator,
        @Advice.This ConsumerRecords records) {
      if (iterator != null) {
        KafkaConsumerInfo kafkaConsumerInfo =
            InstrumentationContext.get(ConsumerRecords.class, KafkaConsumerInfo.class).get(records);
        String group = KafkaConsumerInstrumentationHelper.extractGroup(kafkaConsumerInfo);
        String clusterId =
            KafkaConsumerInstrumentationHelper.extractClusterId(
                kafkaConsumerInfo, InstrumentationContext.get(Metadata.class, String.class));
        String bootstrapServers =
            KafkaConsumerInstrumentationHelper.extractBootstrapServers(kafkaConsumerInfo);
        iterator =
            new TracingIterator(
                iterator, KAFKA_CONSUME, CONSUMER_DECORATE, group, clusterId, bootstrapServers);
      }
    }
  }
}
