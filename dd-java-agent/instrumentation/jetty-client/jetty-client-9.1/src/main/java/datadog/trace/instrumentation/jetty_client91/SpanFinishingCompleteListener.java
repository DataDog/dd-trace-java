package datadog.trace.instrumentation.jetty_client91;

import static datadog.trace.instrumentation.jetty_client91.JettyClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;

public class SpanFinishingCompleteListener implements Response.CompleteListener {
  private final AgentSpan span;

  public SpanFinishingCompleteListener(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void onComplete(Result result) {
    if (result.getResponse().getStatus() <= 0) {
      DECORATE.onError(span, result.getFailure());
    }
    DECORATE.onResponse(span, result.getResponse());
    DECORATE.beforeFinish(span);
    span.finish();
  }
}
