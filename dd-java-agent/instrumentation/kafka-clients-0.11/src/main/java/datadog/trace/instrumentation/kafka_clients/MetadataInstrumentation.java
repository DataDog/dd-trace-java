package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.requests.MetadataResponse;

@AutoService(Instrumenter.class)
public class MetadataInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public MetadataInstrumentation() {
    super("kafka");
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
    return new String[] {packageName + ".KafkaDecorator"};
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
        InstrumentationContext.get(Metadata.class, String.class)
            .put(metadata, newCluster.clusterResource().clusterId());
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
        InstrumentationContext.get(Metadata.class, String.class)
            .put(metadata, response.clusterId());
      }
    }

    public static void muzzleCheck(ConsumerRecord record) {
      // KafkaConsumerInstrumentation only applies for kafka versions with headers
      // Make an explicit call so MetadataInstrumentation does the same
      record.headers();
    }
  }
}
