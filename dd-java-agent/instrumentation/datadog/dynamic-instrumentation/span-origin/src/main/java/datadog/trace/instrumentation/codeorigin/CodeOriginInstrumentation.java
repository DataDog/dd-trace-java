package datadog.trace.instrumentation.codeorigin;

import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule.Tracing;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.OneOf;
import datadog.trace.api.InstrumenterConfig;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Set;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CodeOriginInstrumentation extends Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(CodeOriginInstrumentation.class);
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
    return HierarchyMatchers.declaresMethod(HierarchyMatchers.isAnnotatedWith(matcher))
        .or(
            HierarchyMatchers.implementsInterface(
                HierarchyMatchers.declaresMethod(HierarchyMatchers.isAnnotatedWith(matcher))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        HierarchyMatchers.isAnnotatedWith(matcher),
        "datadog.trace.instrumentation.codeorigin.EntrySpanOriginAdvice");
    transformer.applyAdvice(
        ElementMatchers.isDeclaredBy(
            hasSuperType(isInterface().and(declaresMethod(isAnnotatedWith(matcher))))),
        "datadog.trace.instrumentation.codeorigin.EntrySpanOriginAdvice");
  }
}
