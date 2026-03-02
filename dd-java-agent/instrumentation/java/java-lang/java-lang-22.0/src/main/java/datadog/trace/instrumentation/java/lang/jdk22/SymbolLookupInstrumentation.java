package datadog.trace.instrumentation.java.lang.jdk22;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class SymbolLookupInstrumentation
    implements Instrumenter.ForTypeHierarchy,
        Instrumenter.ForBootstrap,
        Instrumenter.HasMethodAdvice {
  private static final String SYMBOL_LOOKUP = "java.lang.foreign.SymbolLookup";

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // instrument both interface and sub-implementations
    return implementsInterface(named(SYMBOL_LOOKUP)).or(named(SYMBOL_LOOKUP));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isStatic()).and(named("defaultLookup")),
        "datadog.trace.instrumentation.java.lang.jdk22.SymbolLookupAdvices$CaptureDefaultLookup");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("libraryLookup").and(takesArgument(0, named("java.lang.String")))),
        "datadog.trace.instrumentation.java.lang.jdk22.SymbolLookupAdvices$CaptureLibraryName");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("libraryLookup").and(takesArgument(0, named("java.nio.Path")))),
        "datadog.trace.instrumentation.java.lang.jdk22.SymbolLookupAdvices$CaptureLibraryPath");
    transformer.applyAdvice(
        isMethod().and(named("find").and(takesArgument(0, named("java.lang.String")))),
        "datadog.trace.instrumentation.java.lang.jdk22.SymbolLookupAdvices$CaptureMemorySegment");
  }
}
