package datadog.trace.instrumentation.disruptor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Collections;
import java.util.Map;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(InstrumenterModule.class)
public class EventHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public EventHandlerInstrumentation() {
    super("disruptor");
  }

  public static final String CLASS_NAME = "com.lmax.disruptor.EventHandler";

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher hierarchyMatcher() {
    return implementsInterface(named(CLASS_NAME));
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("onEvent")),
        packageName + ".EventHandlerAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".EventHandlerAdvice",
        packageName + ".DisruptorDecorator",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        Long.class.getName(), AgentSpan.class.getName());
  }

}
