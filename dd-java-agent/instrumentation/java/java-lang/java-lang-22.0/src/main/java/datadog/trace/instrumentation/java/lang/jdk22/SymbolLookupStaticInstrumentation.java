package datadog.trace.instrumentation.java.lang.jdk22;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;

public class SymbolLookupStaticInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.ForBootstrap, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "java.lang.foreign.SymbolLookup";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
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
  }
}
