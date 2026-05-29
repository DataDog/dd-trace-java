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

/**
 * Captures the active scope at {@code AsyncCall.<init>} (i.e. the moment {@code
 * Call.enqueue(callback)} was invoked on the user's thread) and stores it in the {@code
 * ContextStore<Runnable, State>} shared with the rest of the concurrent instrumentation. {@code
 * RunnableInstrumentation} then re-activates that scope on {@code AsyncCall.run()} entry, which
 * overrides whatever scope {@code TaskRunner.run()} (or {@code beforeExecute}) put in place from
 * the dispatcher's worker thread.
 *
 * <p>Without this, {@code TaskRunnerInstrumentation} captures whatever scope happens to be active
 * on the worker thread when {@code Dispatcher.promoteAndExecute()} dequeues and submits the call —
 * and when promotion runs from inside {@code Dispatcher.finished()} (i.e. recursively from a
 * <em>different</em> AsyncCall's run()), that scope belongs to the finishing call, not to the
 * caller who actually enqueued this AsyncCall. Result: under concurrent OkHttp load, {@code
 * okhttp.request} spans cross-contaminate between traces.
 *
 * <p>{@code AsyncCall} is an inner class of {@code RealCall} and transitively implements {@link
 * Runnable}.
 */
@AutoService(InstrumenterModule.class)
public final class AsyncCallInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public AsyncCallInstrumentation() {
    // Re-use the existing "okhttp" / "okhttp-3" instrumentation names so we don't introduce a
    // separately-toggleable feature flag (DD_TRACE_OKHTTP_ASYNC_CALL_ENABLED). The capture here
    // is conceptually part of the OkHttp instrumentation — if you disable OkHttp tracing, you
    // also disable this capture, which is the right behavior.
    super("okhttp", "okhttp-3");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "okhttp3.RealCall$AsyncCall",
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
