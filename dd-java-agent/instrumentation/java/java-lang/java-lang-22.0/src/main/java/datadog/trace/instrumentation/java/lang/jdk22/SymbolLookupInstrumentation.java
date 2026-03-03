package datadog.trace.instrumentation.java.lang.jdk22;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class SymbolLookupInstrumentation
    implements Instrumenter.ForTypeHierarchy,
        Instrumenter.ForBootstrap,
        Instrumenter.HasMethodAdvice {
  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // instrument both interface and sub-implementations
    return implementsInterface(named("java.lang.foreign.SymbolLookup"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("find").and(takesArgument(0, named("java.lang.String")))),
        "datadog.trace.instrumentation.java.lang.jdk22.SymbolLookupAdvices$CaptureMemorySegment");
  }
}
