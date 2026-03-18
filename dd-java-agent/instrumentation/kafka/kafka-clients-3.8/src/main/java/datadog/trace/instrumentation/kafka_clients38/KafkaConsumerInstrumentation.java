package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class KafkaConsumerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public KafkaConsumerInstrumentation() {
    super("kafka", "kafka-3.8");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy"); // since 3.8
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isExperimentalKafkaEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>(2);
    contextStores.put("org.apache.kafka.clients.Metadata", "java.lang.String");
    contextStores.put(
        "org.apache.kafka.clients.consumer.ConsumerRecords",
        "datadog.trace.instrumentation.kafka_clients38.KafkaConsumerInfo");
    return Collections.unmodifiableMap(contextStores);
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
        packageName + ".IterableAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("records"))
            .and(takesArgument(0, named("org.apache.kafka.common.TopicPartition")))
            .and(returns(List.class)),
        packageName + ".ListAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("iterator"))
            .and(takesArguments(0))
            .and(returns(Iterator.class)),
        packageName + ".IteratorAdvice");
  }
}
