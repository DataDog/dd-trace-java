package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class MetadataInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public MetadataInstrumentation() {
    super("kafka", "kafka-3.8");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy"); // since 3.8
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.kafka.clients.Metadata";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KafkaDecorator",
      "datadog.trace.instrumentation.kafka_common.KafkaConfigHelper",
      "datadog.trace.instrumentation.kafka_common.KafkaConfigHelper$PendingConfig",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.kafka.clients.Metadata", "java.lang.String");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("update"))
            .and(takesArgument(0, named("org.apache.kafka.common.Cluster"))),
        packageName + ".MetadataUpdateBefore22Advice");
    transformer.applyAdvice(
        isMethod()
            .and(named("update"))
            .and(takesArgument(1, named("org.apache.kafka.common.requests.MetadataResponse"))),
        packageName + ".MetadataUpdate22AndAfterAdvice");
  }
}
