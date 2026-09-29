package datadog.trace.bootstrap;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayDeque;

/**
 * Tracks, per thread, which annotated resource-method invocations (identified by their spans) are
 * currently still on the call stack -- i.e. whose exit advice has not yet run.
 *
 * <p>Used by the JAX-RS/Jakarta-RS instrumentation to tell a synchronous {@code
 * AsyncResponse#resume()}/{@code cancel()} call -- one nested inside the still-running resource
 * method that owns the response -- apart from a genuinely asynchronous one called later, from a
 * different thread or after an intervening unrelated instrumented call. A generic "is any resource
 * method open on this thread" counter is not enough for that: it can't tell one resource method's
 * invocation apart from another's on a shared thread pool, and it can't see past a nested
 * instrumented call (e.g. a {@code @Trace}-annotated helper, or another resource method called
 * synchronously from within this one) that becomes the current active span without popping this
 * stack. Checking whether the specific span object is anywhere on this stack -- not just at the top
 * -- answers the exact question that matters (is this invocation still open, however deeply nested
 * other instrumented calls have gotten in the meantime), without either failure mode.
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

  /**
   * True if {@code span}'s resource-method invocation is still open (its exit advice has not run)
   * anywhere on this thread's stack -- not just as the innermost entry. A resource method can still
   * have work left to do after a nested call (a {@code @Trace} helper, or another resource method
   * invoked synchronously from within this one) becomes the innermost entry in its place; this must
   * still be treated as "open" for the outer invocation's own {@code resume()}/{@code cancel()} to
   * be recognized as synchronous rather than genuinely async.
   */
  public static boolean isOpen(final AgentSpan span) {
    final ArrayDeque<AgentSpan> stack = STACK.get();
    if (stack == null) {
      return false;
    }
    for (final AgentSpan open : stack) {
      if (open == span) {
        return true;
      }
    }
    return false;
  }
}
