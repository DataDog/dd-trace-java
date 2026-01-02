package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import akka.dispatch.Envelope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import net.bytebuddy.asm.Advice;

public class AkkaEnvelopeInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "akka.dispatch.Envelope";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$ConstructAdvice");
  }

  public static class ConstructAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterInit(@Advice.This Envelope zis) {
      capture(InstrumentationContext.get(Envelope.class, State.class), zis);
    }
  }
}
