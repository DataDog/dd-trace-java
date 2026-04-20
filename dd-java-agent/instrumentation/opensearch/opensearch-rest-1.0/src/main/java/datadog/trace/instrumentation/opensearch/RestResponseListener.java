package datadog.trace.instrumentation.opensearch;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseListener;

public class RestResponseListener implements ResponseListener {

  private final ResponseListener listener;
  private final AgentSpan span;

  public RestResponseListener(final ResponseListener listener, final AgentSpan span) {
    this.listener = listener;
    this.span = span;
  }

  @Override
  public void onSuccess(final Response response) {
    if (response.getHost() != null) {
      OpensearchRestClientDecorator.DECORATE.onResponse(span, response);
    }

    try {
      listener.onSuccess(response);
    } finally {
      OpensearchRestClientDecorator.DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void onFailure(final Exception e) {
    OpensearchRestClientDecorator.DECORATE.onError(span, e);

    try {
      listener.onFailure(e);
    } finally {
      OpensearchRestClientDecorator.DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
