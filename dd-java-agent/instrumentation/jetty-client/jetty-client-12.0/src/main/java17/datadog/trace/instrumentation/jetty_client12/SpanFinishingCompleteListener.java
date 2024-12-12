package datadog.trace.instrumentation.jetty_client12;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;

public class SpanFinishingCompleteListener implements Response.CompleteListener {
  private final ContextStore<Request, AgentSpan> contextStore;

  public SpanFinishingCompleteListener(ContextStore<Request, AgentSpan> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public void onComplete(Result result) {
    AgentSpan span = contextStore.get(result.getRequest());
    if (span == null) {
      return;
    }
    if (result.getResponse().getStatus() <= 0) {
      JettyClientDecorator.DECORATE.onError(span, result.getFailure());
    }
    JettyClientDecorator.DECORATE.onResponse(span, result.getResponse());
    JettyClientDecorator.DECORATE.beforeFinish(span);
    span.finish();
  }
}
