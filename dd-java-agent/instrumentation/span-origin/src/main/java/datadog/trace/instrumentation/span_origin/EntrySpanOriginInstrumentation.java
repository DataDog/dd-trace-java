package datadog.trace.instrumentation.span_origin;

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

public abstract class EntrySpanOriginInstrumentation extends Tracing implements ForTypeHierarchy {

  private final OneOf<NamedElement> matcher;

  @SuppressForbidden
  public EntrySpanOriginInstrumentation(String instrumentationName) {
    super(instrumentationName);
    this.matcher = namedOneOf(getAnnotations());
  }

  protected abstract Set<String> getAnnotations();

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.span_origin.EntrySpanOriginAdvice$FindFirstStackTraceElement"
    };
  }

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
    transformer.applyAdvice(
        isAnnotatedWith(matcher),
        "datadog.trace.instrumentation.span_origin.EntrySpanOriginAdvice");
  }
}
