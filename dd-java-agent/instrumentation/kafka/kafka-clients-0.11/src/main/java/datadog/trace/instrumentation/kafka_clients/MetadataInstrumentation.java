package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.kafka_common.KafkaConfigHelper;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.requests.MetadataResponse;

@AutoService(InstrumenterModule.class)
public class MetadataInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public MetadataInstrumentation() {
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
        MetadataInstrumentation.class.getName() + "$MetadataUpdateBefore22Advice");
    transformer.applyAdvice(
        isMethod()
            .and(named("update"))
            .and(takesArgument(1, named("org.apache.kafka.common.requests.MetadataResponse"))),
        MetadataInstrumentation.class.getName() + "$MetadataUpdate22AndAfterAdvice");
  }

  public static class MetadataUpdateBefore22Advice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This final Metadata metadata, @Advice.Argument(0) final Cluster newCluster) {
      if (newCluster != null && !newCluster.isBootstrapConfigured()) {
        String clusterId = newCluster.clusterResource().clusterId();
        InstrumentationContext.get(Metadata.class, String.class).put(metadata, clusterId);
        KafkaConfigHelper.reportPendingConfig(metadata, clusterId);
      }
    }

    public static void muzzleCheck(ConsumerRecord record) {
      // KafkaConsumerInstrumentation only applies for kafka versions with headers
      // Make an explicit call so MetadataInstrumentation does the same
      record.headers();
    }
  }

  public static class MetadataUpdate22AndAfterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This final Metadata metadata, @Advice.Argument(1) final MetadataResponse response) {
      if (response != null) {
        String clusterId = response.clusterId();
        InstrumentationContext.get(Metadata.class, String.class).put(metadata, clusterId);
        KafkaConfigHelper.reportPendingConfig(metadata, clusterId);
      }
    }

    public static void muzzleCheck(ConsumerRecord record) {
      // KafkaConsumerInstrumentation only applies for kafka versions with headers
      // Make an explicit call so MetadataInstrumentation does the same
      record.headers();
    }
  }
}
