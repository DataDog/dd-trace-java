package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import akka.dispatch.Envelope;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class AkkaEnvelopeInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public AkkaEnvelopeInstrumentation() {
    super("akka_actor_send", "akka_actor", "akka_concurrent", "java_concurrent");
  }

  @Override
  public String instrumentedType() {
    return "akka.dispatch.Envelope";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("akka.dispatch.Envelope", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$ConstructAdvice");
  }

  public static class ConstructAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterInit(@Advice.This Envelope zis) {
      capture(InstrumentationContext.get(Envelope.class, State.class), zis, true);
    }
  }
}
