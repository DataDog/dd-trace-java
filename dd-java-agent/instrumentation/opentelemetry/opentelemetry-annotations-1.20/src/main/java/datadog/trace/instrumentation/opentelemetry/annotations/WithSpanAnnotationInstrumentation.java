package datadog.trace.instrumentation.opentelemetry.annotations;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.isAnnotatedWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.hasParameters;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.whereAny;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class WithSpanAnnotationInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public WithSpanAnnotationInstrumentation() {
    super("opentelemetry-annotations", "opentelemetry-annotations-1.20");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isTraceOtelEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.opentelemetry.instrumentation.annotations.WithSpan";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return declaresMethod(isAnnotatedWith(named(hierarchyMarkerType())));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      this.packageName + ".WithSpanDecorator",
      this.packageName + ".WithSpanDecorator$1", // Switch over enum generated class
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    ElementMatcher.Junction<MethodDescription> annotatedMethodMatcher =
        isAnnotatedWith(named(hierarchyMarkerType()));
    ElementMatcher.Junction<MethodDescription> annotatedParametersMatcher =
        hasParameters(
            whereAny(
                isAnnotatedWith(
                    named("io.opentelemetry.instrumentation.annotations.SpanAttribute"))));
    // Apply transformation without parameter capture
    transformation.applyAdvice(
        annotatedMethodMatcher.and(not(annotatedParametersMatcher)),
        this.packageName + ".WithSpanAdvice");
    // Apply transformation with parameter capture
    transformation.applyAdvice(
        annotatedMethodMatcher.and(annotatedParametersMatcher),
        this.packageName + ".WithSpanAttributeAdvice");
  }
}
