package datadog.trace.instrumentation.java.lang.jdk22;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;

public class LinkerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.ForBootstrap, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "jdk.internal.foreign.abi.AbstractLinker";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("downcallHandle"))
            .and(takesArgument(0, named("java.lang.foreign.MemorySegment"))),
        "datadog.trace.instrumentation.java.lang.jdk22.DownCallWrapAdvice");
  }
}
