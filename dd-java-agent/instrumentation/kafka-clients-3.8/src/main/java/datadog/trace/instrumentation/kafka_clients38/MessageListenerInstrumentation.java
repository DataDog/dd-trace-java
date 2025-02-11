package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class MessageListenerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public MessageListenerInstrumentation() {
    super("kafka", "kafka-3.8");
  }

  @Override
  public boolean isEnabled() {
    return InstrumenterConfig.get().isCodeOriginEnabled() && super.isEnabled();
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy"); // since 3.8
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.kafka.listener.MessageListener";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("onMessage")),
        datadog.trace.instrumentation.codeorigin.EntrySpanOriginAdvice.class.getName());
  }
}
