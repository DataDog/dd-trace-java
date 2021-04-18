package datadog.trace.instrumentation.ignite.v2.cache;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.lang.IgniteInClosure;

/**
 * FutureCallback, which automatically finishes the span and annotates with any appropriate metadata
 * on a potential failure.
 */
public class SpanFinishingCallback implements IgniteInClosure<IgniteFuture<?>> {

  /** Span that we should finish and annotate when the future is complete. */
  private final AgentSpan span;

  public SpanFinishingCallback(final AgentSpan span) {
    this.span = span;
  }

  @Override
  public void apply(IgniteFuture<?> igniteFuture) {
    IgniteCacheDecorator.DECORATE.beforeFinish(span);

    System.err.println("Called callback: " + igniteFuture);

    try {
      Object result = igniteFuture.get();
      IgniteCacheDecorator.DECORATE.beforeFinish(span);
      IgniteCacheDecorator.DECORATE.onResult(span, result);
    } catch (Exception e) {
      IgniteCacheDecorator.DECORATE.onError(span, e);
    } finally {
      span.finish();
    }
  }
}
