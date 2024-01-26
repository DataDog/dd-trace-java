package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.isAnnotatedWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class DoNotTraceAnnotationInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  @SuppressForbidden
  public DoNotTraceAnnotationInstrumentation() {
    super("not-not-trace", "do-not-trace-annotation");
  }

  @Override
  public String hierarchyMarkerType() {
    return "datadog.trace.api.DoNotTrace";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return declaresMethod(isAnnotatedWith(named(hierarchyMarkerType())));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TraceDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isAnnotatedWith(named(hierarchyMarkerType())), packageName + ".DoNotTraceAdvice");
  }
}
