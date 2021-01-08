package datadog.trace.instrumentation.twilio;

import static datadog.trace.instrumentation.twilio.TwilioClientDecorator.DECORATE;

import com.google.common.util.concurrent.FutureCallback;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/**
 * FutureCallback, which automatically finishes the span and annotates with any appropriate metadata
 * on a potential failure.
 */
public class SpanFinishingCallback implements FutureCallback {

  /** Span that we should finish and annotate when the future is complete. */
  private final AgentSpan span;

  public SpanFinishingCallback(final AgentSpan span) {
    this.span = span;
  }

  @Override
  public void onSuccess(final Object result) {
    DECORATE.beforeFinish(span);
    DECORATE.onResult(span, result);
    span.finish();
  }

  @Override
  public void onFailure(final Throwable t) {
    DECORATE.onError(span, t);
    DECORATE.beforeFinish(span);
    span.finish();
  }
}
