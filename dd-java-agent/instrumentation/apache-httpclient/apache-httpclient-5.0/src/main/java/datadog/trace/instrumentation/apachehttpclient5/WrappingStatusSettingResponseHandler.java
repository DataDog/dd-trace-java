package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;

public class WrappingStatusSettingResponseHandler implements HttpClientResponseHandler {
  final AgentSpan span;
  final HttpClientResponseHandler handler;

  public WrappingStatusSettingResponseHandler(
      final AgentSpan span, final HttpClientResponseHandler handler) {
    this.span = span;
    this.handler = handler;
  }

  @Override
  public Object handleResponse(final ClassicHttpResponse response)
      throws HttpException, IOException {
    if (null != span) {
      DECORATE.onResponse(span, response);
    }
    return handler.handleResponse(response);
  }
}
