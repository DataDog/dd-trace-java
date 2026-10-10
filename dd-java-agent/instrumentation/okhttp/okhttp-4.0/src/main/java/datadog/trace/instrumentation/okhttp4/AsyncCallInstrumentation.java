package datadog.trace.instrumentation.okhttp4;

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

/**
 * OkHttp 4.x+ variant of the {@code AsyncCall.<init>} scope capture. Identical in behavior to the
 * {@code okhttp-3.0} module's instrumentation — see that class for the full explanation of the
 * dispatcher-recursion failure mode — but matches the relocated 4.x type.
 *
 * <p>{@code AsyncCall} is an inner class of {@code RealCall} and transitively implements {@link
 * Runnable}. OkHttp 4.x moved {@code RealCall} from {@code okhttp3} into {@code
 * okhttp3.internal.connection}, so the nested type is {@code
 * okhttp3.internal.connection.RealCall$AsyncCall}.
 */
@AutoService(InstrumenterModule.class)
public final class AsyncCallInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public AsyncCallInstrumentation() {
    // Re-use the existing "okhttp" instrumentation name so this capture is enabled/disabled with
    // OkHttp tracing as a whole, rather than introducing a separately-toggleable feature flag.
    //
    // This is a ContextTracking module (like RunnableInstrumentation, which consumes the state we
    // write) rather than a Tracing module: its sole job is to propagate the captured scope through
    // the shared ContextStore<Runnable, State>, not to create spans of its own.
    super("okhttp", "okhttp-4");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "okhttp3.internal.connection.RealCall$AsyncCall", // OkHttp 4.x+
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
