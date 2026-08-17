package datadog.trace.instrumentation.scala;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collections;
import scala.util.Failure;
import scala.util.Success;
import scala.util.Try;

public class PromiseHelper {
  public static final boolean completionPriority =
      InstrumenterConfig.get()
          .isIntegrationEnabled(
              Collections.singletonList("scala_promise_completion_priority"), false);

  /**
   * Get the {@code Try} that should be associated with the {@code Context}. Will create a new copy
   * of the {@code Try} if the existing one already has a different {@code Context} associated.
   *
   * @param resolved the current Try
   * @param context the current context
   * @param existing the currently stored context for the Try
   * @return the Try that should be associated with the context
   */
  public static <T> Try<T> getTry(
      final Try<T> resolved, final Context context, final Context existing) {
    // Check if the new context is the same as the currently stored one
    if (existing == context) {
      return resolved;
    }

    // Otherwise we need to create a new Try to associate the context with
    if (resolved instanceof Success) {
      Success<T> success = (Success<T>) resolved;
      return new Success<>(success.value());
    } else if (resolved instanceof Failure) {
      Failure<T> failure = (Failure<T>) resolved;
      return new Failure<>(failure.exception());
    }
    return resolved;
  }

  /**
   * Activate the {@code Context} stored in the {@code State} for the active task, if any, and mark
   * migration accordingly.
   *
   * @param state the State related to the task becoming active.
   * @return the active ContextScope
   */
  public static ContextScope runWithContext(State state) {
    if (state == null) {
      return null;
    }
    Context context = state.getContext();
    if (context != Context.root() && context != Context.current()) {
      return AdviceUtils.startTaskScope(state);
    } else {
      state.closeContinuation();
    }
    return null;
  }

  /**
   * Transfer and capture the {@code Context} from the {@code Try} to the task, and store or swap
   * out the existing {@code Continuation} if any.
   *
   * @param tryStore the ContextStore for the Try
   * @param resolved the Try in question
   * @param taskStore the ContextStore for the task
   * @param task the task in question
   * @param state the current State associated with the task
   * @return the current or updated state
   */
  public static <K> State executeCaptureContext(
      ContextStore<Try, Context> tryStore,
      Try<?> resolved,
      ContextStore<K, State> taskStore,
      K task,
      State state) {
    final Context context = tryStore.get(resolved);
    // Check if there's no new context, or it's the same as the stored one
    if (null == context || (null != state && state.getContext() == context)) {
      return state;
    }
    ContextContinuation continuation = context.capture();
    ContextContinuation existing = null;
    if (null != state) {
      existing = state.getAndResetContinuation();
    } else {
      state = State.FACTORY.create();
      taskStore.put(task, state);
    }
    state.setOrCancelContinuation(continuation);
    if (null != existing) {
      existing.release();
    }
    return state;
  }
}
