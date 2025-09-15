package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This instrumentation saves additional information from the KafkaConsumer, such as consumer group
 * and cluster ID, in the context store for later use.
 */
@AutoService(InstrumenterModule.class)
public final class LegacyKafkaConsumerInfoInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public LegacyKafkaConsumerInfoInstrumentation() {
    super("kafka", "kafka-3.8");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy"); // since 3.8
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>(4);
    contextStores.put("org.apache.kafka.clients.Metadata", "java.lang.String");
    contextStores.put(
        "org.apache.kafka.clients.consumer.ConsumerRecords", KafkaConsumerInfo.class.getName());
    contextStores.put(
        "org.apache.kafka.clients.consumer.internals.ConsumerCoordinator",
        KafkaConsumerInfo.class.getName());
    // new - we are storing the ConsumerDelegate instead of the KafkaConsumer
    contextStores.put(
        "org.apache.kafka.clients.consumer.internals.ConsumerDelegate",
        KafkaConsumerInfo.class.getName());
    return contextStores;
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.kafka.clients.consumer.internals.ConsumerDelegate";
  }

  // new - we are instrumenting the ConsumerDelegate class instead of the KafkaConsumer class
  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(declaresField(named("coordinator")));
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
        packageName + ".LegacyConstructorAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("poll"))
            .and(takesArguments(1))
            .and(returns(named("org.apache.kafka.clients.consumer.ConsumerRecords"))),
        packageName + ".RecordsAdvice");
  }
}
