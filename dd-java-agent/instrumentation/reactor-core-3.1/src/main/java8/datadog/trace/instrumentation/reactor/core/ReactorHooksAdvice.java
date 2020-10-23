package datadog.trace.instrumentation.reactor.core;

import net.bytebuddy.asm.Advice;

public class ReactorHooksAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void postStaticInitializer() {
    TracingOperator.registerOnEachOperator();
  }
}
