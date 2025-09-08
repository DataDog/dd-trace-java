package datadog.trace.instrumentation.hazelcast39;

import static datadog.trace.instrumentation.hazelcast39.ClientInvocationDecorator.DECORATE;

import com.hazelcast.core.ExecutionCallback;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class SpanFinishingExecutionCallback<V> implements ExecutionCallback<V> {

  /** Span that we should finish and annotate when the future is complete. */
  private final AgentSpan span;

  public SpanFinishingExecutionCallback(final AgentSpan span) {
    this.span = span;
  }

  @Override
  public void onResponse(V response) {
    DECORATE.beforeFinish(span);
    DECORATE.onResult(span, response);
    span.finish();
  }

  @Override
  public void onFailure(final Throwable t) {
    DECORATE.onError(span, t);
    DECORATE.beforeFinish(span);
    span.finish();
  }
}
