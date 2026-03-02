package datadog.trace.instrumentation.java.lang.jdk22;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class LinkerInstrumentation
    implements Instrumenter.ForTypeHierarchy,
        Instrumenter.ForBootstrap,
        Instrumenter.HasMethodAdvice {
  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("java.lang.foreign.Linker"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("downcallHandle")),
        "datadog.trace.instrumentation.java.lang.jdk22.DownCallWrapAdvice");
  }
}
