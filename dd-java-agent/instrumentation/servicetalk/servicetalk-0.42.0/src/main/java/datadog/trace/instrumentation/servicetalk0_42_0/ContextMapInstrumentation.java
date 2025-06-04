package datadog.trace.instrumentation.servicetalk0_42_0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.servicetalk.context.api.ContextMap;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ContextMapInstrumentation extends ServiceTalkInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.servicetalk.concurrent.api.CopyOnWriteContextMap";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(isPrivate())
            .and(takesArguments(1))
            .and(
                takesArgument(
                    0,
                    named("io.servicetalk.concurrent.api.CopyOnWriteContextMap$CopyContextMap"))),
        getClass().getName() + "$Construct");
  }

  private static final class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This ContextMap contextMap) {
      // Capture an active span on ST context copy to support versions prior to 0.42.56 that did not
      // have captureContext
      InstrumentationContext.get(ContextMap.class, AgentSpan.class)
          .put(contextMap, AgentTracer.activeSpan());
    }
  }
}
