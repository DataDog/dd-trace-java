package datadog.trace.instrumentation.codeorigin;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule.Tracing;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.OneOf;
import datadog.trace.api.InstrumenterConfig;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Set;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class CodeOriginInstrumentation extends Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private final OneOf<NamedElement> matcher;

  @SuppressForbidden
  public CodeOriginInstrumentation(String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
    this.matcher = NameMatchers.namedOneOf(getAnnotations());
  }

  @Override
  public boolean isEnabled() {
    return InstrumenterConfig.get().isCodeOriginEnabled() && super.isEnabled();
  }

  protected abstract Set<String> getAnnotations();

  @Override
  public String hierarchyMarkerType() {
    return null; // no particular marker type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    ElementMatcher.Junction<TypeDescription> matcher =
        declaresMethod(isAnnotatedWith(this.matcher));
    if (InstrumenterConfig.get().isCodeOriginInterfaceSupport()) {
      matcher = matcher.or(implementsInterface(declaresMethod(isAnnotatedWith(this.matcher))));
    }
    return matcher;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isAnnotatedWith(matcher), "datadog.trace.instrumentation.codeorigin.EntrySpanOriginAdvice");
    if (InstrumenterConfig.get().isCodeOriginInterfaceSupport()) {
      transformer.applyAdvice(
          isDeclaredBy(implementsInterface(declaresMethod(isAnnotatedWith(matcher)))),
          "datadog.trace.instrumentation.codeorigin.EntrySpanOriginAdvice");
    }
  }
}
