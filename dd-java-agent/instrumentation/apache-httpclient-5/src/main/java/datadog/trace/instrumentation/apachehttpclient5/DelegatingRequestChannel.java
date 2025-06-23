package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.context.Context.current;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpclient5.HttpHeadersInjectAdapter.SETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpContext;

public class DelegatingRequestChannel implements RequestChannel {
  private final RequestChannel delegate;
  private final AgentSpan span;

  public DelegatingRequestChannel(RequestChannel requestChannel, AgentSpan span) {
    this.delegate = requestChannel;
    this.span = span;
  }

  @Override
  public void sendRequest(HttpRequest request, EntityDetails entityDetails, HttpContext context)
      throws HttpException, IOException {
    DECORATE.onRequest(span, request);
    DECORATE.injectContext(current().with(span), request, SETTER);
    delegate.sendRequest(request, entityDetails, context);
  }
}
