package datadog.trace.instrumentation.pekko.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.pekko.dispatch.Envelope;

@AutoService(InstrumenterModule.class)
public class PekkoEnvelopeInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public PekkoEnvelopeInstrumentation() {
    super("pekko_actor_send", "pekko_actor", "pekko_concurrent", "java_concurrent");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.dispatch.Envelope";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.pekko.dispatch.Envelope", State.class.getName());
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
