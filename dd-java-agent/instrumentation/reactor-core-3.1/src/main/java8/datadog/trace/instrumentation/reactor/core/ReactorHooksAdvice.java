package datadog.trace.instrumentation.reactor.core;

import net.bytebuddy.asm.Advice;
import reactor.core.publisher.Hooks;

public class ReactorHooksAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void postStaticInitializer() {
    Hooks.onEachOperator(TracingPublishers.class.getName(), TracingPublishers::wrap);
  }
}
