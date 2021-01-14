package datadog.trace.instrumentation.scala;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import scala.util.Failure;
import scala.util.Success;
import scala.util.Try;

public class PromiseHelper {
  public static final boolean completionPriority =
      Config.get()
          .isIntegrationEnabled(
              Collections.singletonList("scala_promise_completion_priority"), false);

  /**
   * Get the {@code Span} that should be associated with the {@code Try} completing this {@code
   * Promise}.
   *
   * @return the Span or null
   */
  public static AgentSpan getSpan() {
    AgentSpan span = null;
    final TraceScope scope = activeScope();
    if (null != scope && scope.isAsyncPropagating()) {
      if (scope instanceof AgentScope) {
        span = ((AgentScope) scope).span();
      } else {
        span = activeSpan();
      }
    }
    return span;
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
   * Capture the {@code Span} and store or swap out the existing {@code Continuation} if any.
   *
   * @param span the Span to capture
   * @param state the State to update
   * @return the state where the Continuation was stored
   */
  public static State handleSpan(final AgentSpan span, State state) {
    if (completionPriority && null != span) {
      // TODO Optimization
      // One could possibly peek at the Span in the existing Continuation and not do anything if it
      // was the same Span
      TraceScope.Continuation continuation = captureSpan(span);
      TraceScope.Continuation existing = null;
      if (null != state) {
        existing = state.getAndResetContinuation();
      } else {
        state = State.FACTORY.create();
      }
      state.setOrCancelContinuation(continuation);
      if (null != existing) {
        existing.cancel();
      }
    }
    return state;
  }
}
