package datadog.trace.instrumentation.apachehttpclient;

import static datadog.trace.instrumentation.apachehttpclient.ApacheHttpClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;

public class WrappingStatusSettingResponseHandler implements ResponseHandler {
  final AgentSpan span;
  final ResponseHandler handler;

  public WrappingStatusSettingResponseHandler(final AgentSpan span, final ResponseHandler handler) {
    this.span = span;
    this.handler = handler;
  }

  @Override
  public Object handleResponse(final HttpResponse response)
      throws ClientProtocolException, IOException {
    if (null != span) {
      DECORATE.onResponse(span, response);
    }
    return handler.handleResponse(response);
  }
}
