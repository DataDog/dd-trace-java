package datadog.trace.instrumentation.scala;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;

import datadog.context.Context;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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
   * Get the {@code Span} that should be associated with the {@code Try} completing this {@code
   * Promise}.
   *
   * @return the Span or null
   */
  public static AgentSpan getSpan() {
    final AgentSpan span = activeSpan();
    if (null != span && span.isValid() && isAsyncPropagationEnabled()) {
      return span;
    }
    return null;
  }

  /**
   * Get the {@code Try} that should be associated with the {@code Span}. Will create a new copy of
   * the {@code Try} if the existing one already has a different {@code Span} associated.
   *
   * @param resolved the current Try
   * @param span the current Span
   * @param existing the currently stored Span for the Try
   * @return the Try that should be associated with the Span
   */
  public static <T> Try<T> getTry(
      final Try<T> resolved, final AgentSpan span, final AgentSpan existing) {
    // Check if the new Span is the same as the currently stored one
    if (existing == span) {
      return resolved;
    }

    // Otherwise we need to create a new Try to associate the Span with
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
   * Activate the {@code AgentScope} stored in the {@code State} for the active task, if any, and
   * mark migration accordingly.
   *
   * @param state the State related to the task becoming active.
   * @return tha active AgentScope
   */
  public static AgentScope runActivateSpan(State state) {
    if (state == null) {
      return null;
    }
    AgentSpan capturedSpan = state.getSpan();
    if (capturedSpan != null) {
      AgentSpan activeSpan = activeSpan();
      if (capturedSpan != activeSpan) {
        return AdviceUtils.startTaskScope(state);
      } else {
        state.closeContinuation();
      }
    }
    return null;
  }

  /**
   * Transfer and capture the {@code Span} from the {@code Try} to the task, and store or swap out
   * the existing {@code Continuation} if any.
   *
   * @param tryStore the ContextStore for the Try
   * @param resolved the Try in question
   * @param taskStore the ContextStore for the task
   * @param task the task in question
   * @param state the current State associated with the task
   * @return the current or updated state
   */
  public static <K> State executeCaptureSpan(
      ContextStore<Try, Context> tryStore,
      Try<?> resolved,
      ContextStore<K, State> taskStore,
      K task,
      State state) {
    final Context context = tryStore.get(resolved);
    if (context != null) {
      final AgentSpan span = AgentSpan.fromContext(context);
      // Check if the new Span is the same as the currently stored one
      if (null != state && state.getSpan() == span) {
        return state;
      }
      AgentScope.Continuation continuation = captureSpan(span);
      AgentScope.Continuation existing = null;
      if (null != state) {
        existing = state.getAndResetContinuation();
      } else {
        state = State.FACTORY.create();
        taskStore.put(task, state);
      }
      state.setOrCancelContinuation(continuation);
      if (null != existing) {
        existing.cancel();
      }
    }
    return state;
  }
}
