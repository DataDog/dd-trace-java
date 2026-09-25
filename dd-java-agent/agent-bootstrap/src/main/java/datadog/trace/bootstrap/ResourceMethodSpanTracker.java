package datadog.trace.bootstrap;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayDeque;

/**
 * Tracks, per thread, which annotated resource-method invocation (identified by its span) is
 * currently the innermost one still on the call stack -- i.e. whose exit advice has not yet run.
 *
 * <p>Used by the JAX-RS/Jakarta-RS instrumentation to tell a synchronous {@code
 * AsyncResponse#resume()}/{@code cancel()} call -- one nested inside the still-running resource
 * method that owns the response -- apart from a genuinely asynchronous one called later, from a
 * different thread or after an intervening unrelated instrumented call. A generic "is any resource
 * method open on this thread" counter is not enough for that: it can't tell one resource method's
 * invocation apart from another's on a shared thread pool, and it can't see past a nested
 * instrumented call (e.g. a {@code @Trace}-annotated helper) that becomes the current active span
 * without popping this stack. Comparing the specific span object against the top of this stack
 * answers the exact question that matters, without either failure mode.
 *
 * <p>Deliberately bootstrap-loaded (like {@link CallDepthThreadLocalMap}) rather than living on a
 * per-instrumentation helper class: helper classes are injected once per target classloader, so a
 * container that loads the resource-method advice and the AsyncResponse advice into different
 * classloaders (e.g. a modular server where the JAX-RS runtime and the deployed application are in
 * separate classloaders) would otherwise give each advice its own, disconnected copy of this state.
 */
public final class ResourceMethodSpanTracker {

  private static final ThreadLocal<ArrayDeque<AgentSpan>> STACK = new ThreadLocal<>();

  private ResourceMethodSpanTracker() {}

  public static void enter(final AgentSpan span) {
    ArrayDeque<AgentSpan> stack = STACK.get();
    if (stack == null) {
      stack = new ArrayDeque<>(4);
      STACK.set(stack);
    }
    stack.push(span);
  }

  public static void exit() {
    final ArrayDeque<AgentSpan> stack = STACK.get();
    if (stack != null) {
      stack.pop();
    }
  }

  /** True if {@code span} is the innermost still-open resource-method invocation on this thread. */
  public static boolean isInnermost(final AgentSpan span) {
    final ArrayDeque<AgentSpan> stack = STACK.get();
    return stack != null && stack.peek() == span;
  }
}
