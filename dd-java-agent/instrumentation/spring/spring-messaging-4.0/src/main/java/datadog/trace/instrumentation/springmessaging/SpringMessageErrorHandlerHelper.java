package datadog.trace.instrumentation.springmessaging;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;

public final class SpringMessageErrorHandlerHelper {
  private static final ThreadLocal<Integer> AWS_LISTENER_DEPTH = ThreadLocal.withInitial(() -> 0);
  private static final ThreadLocal<AgentScope.Continuation> PENDING_CONTINUATION =
      new ThreadLocal<>();

  private SpringMessageErrorHandlerHelper() {}

  public static void enterAwsListenerInvocation() {
    AWS_LISTENER_DEPTH.set(AWS_LISTENER_DEPTH.get() + 1);
  }

  public static void exitAwsListenerInvocation() {
    int depth = AWS_LISTENER_DEPTH.get() - 1;
    if (depth <= 0) {
      AWS_LISTENER_DEPTH.remove();
    } else {
      AWS_LISTENER_DEPTH.set(depth);
    }
  }

  public static boolean isInAwsListenerInvocation() {
    return AWS_LISTENER_DEPTH.get() > 0;
  }

  public static void capturePendingContinuation(AgentSpan span) {
    if (span == null) {
      return;
    }
    AgentScope.Continuation existing = PENDING_CONTINUATION.get();
    if (existing != null) {
      existing.cancel();
    }
    PENDING_CONTINUATION.set(captureSpan(span));
  }

  public static void transferPendingContinuation(State state) {
    AgentScope.Continuation continuation = PENDING_CONTINUATION.get();
    PENDING_CONTINUATION.remove();
    if (continuation == null) {
      return;
    }
    if (state == null) {
      continuation.cancel();
      return;
    }
    state.setOrCancelContinuation(continuation);
  }

  public static void clearPendingContinuation() {
    AgentScope.Continuation continuation = PENDING_CONTINUATION.get();
    PENDING_CONTINUATION.remove();
    if (continuation != null) {
      continuation.cancel();
    }
  }

  public static AgentScope activateContinuation(State state) {
    if (state == null) {
      return null;
    }
    AgentScope.Continuation continuation = state.getAndResetContinuation();
    return continuation != null ? continuation.activate() : null;
  }

  public static void cancelContinuation(State state) {
    if (state != null) {
      state.closeContinuation();
    }
  }
}
