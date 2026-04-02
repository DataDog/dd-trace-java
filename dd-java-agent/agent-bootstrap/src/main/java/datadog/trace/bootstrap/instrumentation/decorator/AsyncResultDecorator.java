package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtension;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtensions;

/**
 * This decorator handles asynchronous result types, finishing spans only when the async calls are
 * complete. The different async types are supported using {@link AsyncResultExtension} that should
 * be registered using {@link AsyncResultExtensions#register(AsyncResultExtension)} first.
 */
public abstract class AsyncResultDecorator extends BaseDecorator {

  /**
   * Look for asynchronous result and decorate it with span finisher. If the result is not
   * asynchronous, it will be return unmodified and span will be finished.
   *
   * @param result The result to check type.
   * @param span The related span to finish.
   * @return An asynchronous result that will finish the span if the result is asynchronous, the
   *     original result otherwise.
   */
  public Object wrapAsyncResultOrFinishSpan(
      final Object result, final Class<?> methodReturnType, final AgentSpan span) {
    Object applied = AsyncResultExtensions.wrapAsyncResult(result, methodReturnType, span);
    if (applied != null) {
      return applied;
    }
    // If no extension was applied, immediately finish the span and return the original result
    span.finish();
    return result;
  }
}
