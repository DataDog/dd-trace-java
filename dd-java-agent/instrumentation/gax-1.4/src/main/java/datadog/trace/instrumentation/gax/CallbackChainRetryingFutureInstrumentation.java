package datadog.trace.instrumentation.gax;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * Cancels the trace continuation captured on a gax retry attempt's completion listener when that
 * listener is superseded.
 *
 * <p>{@code AttemptCallable.call()} calls {@code setAttemptFuture} twice per attempt: first with a
 * {@code NonCancellableFuture} placeholder (a reservation that is never completed), then with the
 * real RPC future. {@code CallbackChainRetryingFuture.setAttemptFuture} registers a fresh {@code
 * AttemptCompletionListener} each time and stores it in {@code attemptFutureCompletionListener}.
 * The guava {@code AbstractFuture} instrumentation captures a continuation onto each listener. The
 * placeholder's listener never runs (its future never completes), so its continuation would never
 * be activated or canceled.
 *
 * <p>Each {@code setAttemptFuture} overwrites the previous listener: that overwrite is the
 * abandonment signal. We capture the previous listener on entry but cancel its continuation on
 * exit, once the field has actually been replaced, so a listener GAX still treats as active (early
 * return, or a completion racing the overwrite) keeps its continuation.
 */
@AutoService(InstrumenterModule.class)
public class CallbackChainRetryingFutureInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CallbackChainRetryingFutureInstrumentation() {
    super("gax", "gax-1.4");
  }

  @Override
  public String instrumentedType() {
    return "com.google.api.gax.retrying.CallbackChainRetryingFuture";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setAttemptFuture")
            .and(takesArguments(1))
            .and(takesArgument(0, named("com.google.api.core.ApiFuture"))),
        CallbackChainRetryingFutureInstrumentation.class.getName() + "$SetAttemptFutureAdvice");
  }

  public static class SetAttemptFutureAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Runnable capturePrevious(
        @Advice.FieldValue("attemptFutureCompletionListener") final Runnable previousListener) {
      return previousListener;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void cancelSuperseded(
        @Advice.Enter final Runnable previousListener,
        @Advice.FieldValue("attemptFutureCompletionListener") final Runnable newListener) {
      // Only cancel once the field has actually been replaced: GAX may return early without
      // reassigning, and a listener still treated as active must keep its continuation.
      if (previousListener != null && previousListener != newListener) {
        final ContextStore<Runnable, State> contextStore =
            InstrumentationContext.get(Runnable.class, State.class);
        final State state = contextStore.remove(previousListener);
        if (state != null) {
          state.closeContinuation();
        }
      }
    }
  }
}
