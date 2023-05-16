package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpclient5.HttpHeadersInjectAdapter.SETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.IOException;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpContext;

public class DelegatingRequestChannel implements RequestChannel {
  private final RequestChannel delegate;
  private final AgentScopeContext context;
  private final AgentSpan span;

  public DelegatingRequestChannel(
      RequestChannel requestChannel, AgentScopeContext context, AgentSpan span) {
    this.delegate = requestChannel;
    this.context = context;
    this.span = span;
  }

  @Override
  public void sendRequest(HttpRequest request, EntityDetails entityDetails, HttpContext context)
      throws HttpException, IOException {
    DECORATE.onRequest(span, request);

    propagate().inject(span, request, SETTER);
    propagate()
        .injectPathwayContext(
            this.context, request, SETTER, HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);
    delegate.sendRequest(request, entityDetails, context);
  }
}
