package datadog.trace.instrumentation.jetty_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.jetty_client.HeadersInjectAdapter.SETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.util.List;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

public class JettyClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final CharSequence JETTY_CLIENT = UTF8BytesString.create("jetty-client");
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create("http.request");
  public static final JettyClientDecorator DECORATE = new JettyClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jetty-client"};
  }

  @Override
  protected CharSequence component() {
    return JETTY_CLIENT;
  }

  @Override
  protected String method(final Request httpRequest) {
    return httpRequest.getMethod();
  }

  @Override
  protected URI url(final Request httpRequest) {
    return httpRequest.getURI();
  }

  @Override
  protected int status(final Response httpResponse) {
    return httpResponse.getStatus();
  }

  public AgentSpan prepareSpan(
      AgentSpan span, Request request, List<Response.ResponseListener> listeners) {
    // Add listener to the back of the list.
    listeners.add(new SpanFinishingCompleteListener(span));

    span.setMeasured(true);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    propagate().inject(span, request, SETTER);
    return span;
  }
}
