package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.Metadata;
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("update")),
        MetadataInstrumentation.class.getName() + "$MetadataUpdateAdvice");
  }

  public static class MetadataUpdateAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This final Metadata metadata, @Advice.Argument(1) final MetadataResponse response) {
      if (response != null) {
        InstrumentationContext.get(Metadata.class, String.class)
            .put(metadata, response.clusterId());
      }
    }
  }
}
