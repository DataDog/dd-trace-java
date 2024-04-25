package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.isAnnotatedWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import datadog.trace.agent.tooling.Instrumenter.ForTypeHierarchy;
import datadog.trace.agent.tooling.InstrumenterModule.Tracing;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.OneOf;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Set;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class SpanOriginInstrumentation extends Tracing implements ForTypeHierarchy {

  private final OneOf<NamedElement> matcher;

  @SuppressForbidden
  public SpanOriginInstrumentation(String instrumentationName) {
    super(instrumentationName);
    this.matcher = namedOneOf(getAnnotations());
  }

  protected abstract Set<String> getAnnotations();

  @Override
  public String hierarchyMarkerType() {
    return null; // no particular marker type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return declaresMethod(isAnnotatedWith(matcher));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    System.out.println("SpanOriginInstrumentation.methodAdvice");
    transformer.applyAdvice(
        isAnnotatedWith(matcher),
        "datadog.trace.instrumentation.trace_annotation.SpanOriginAdvice");
  }
}
