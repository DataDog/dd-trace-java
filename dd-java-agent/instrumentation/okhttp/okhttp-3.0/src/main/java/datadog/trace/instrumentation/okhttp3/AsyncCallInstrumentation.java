package datadog.trace.instrumentation.okhttp3;

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

@AutoService(InstrumenterModule.class)
public final class AsyncCallInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public AsyncCallInstrumentation() {
    super("okhttp", "okhttp-3");
  }

  @Override
  public String muzzleDirective() {
    // 3.x only: okhttp3.RealCall$AsyncCall relocated in 4.x (handled by the okhttp-4.0 module).
    return "okhttp-async-3";
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "okhttp3.RealCall$AsyncCall", // OkHttp 3.x
    };
  }

  @Override
  public Map<String, String> contextStore() {
    // Same Runnable -> State store that RunnableInstrumentation reads from.
    return singletonMap("java.lang.Runnable", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
  }

  public static final class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureScope(@Advice.This Runnable asyncCall) {
      // AdviceUtils.capture is a no-op when async propagation is disabled or there's no active
      // span — same behavior as the rest of the concurrent instrumentation.
      capture(InstrumentationContext.get(Runnable.class, State.class), asyncCall);
    }
  }
}
