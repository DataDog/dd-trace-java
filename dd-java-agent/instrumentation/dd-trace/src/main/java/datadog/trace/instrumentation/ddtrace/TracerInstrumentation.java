package datadog.trace.instrumentation.ddtrace;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class TracerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public TracerInstrumentation() {
    super("dd-trace");
  }

  @Override
  public String hierarchyMarkerType() {
    return "datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI";
    // "datadog.trace.instrumentation.ddtrace.AgentTracer.TracerAPI";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("startSpan").and(isMethod()), packageName + ".StartSpanAdvice");
    transformation.applyAdvice(named("flush").and(isMethod()), packageName + ".FlushAdvice");
  }
}
