package datadog.trace.instrumentation.apachehttpasyncclient;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpasyncclient.HttpHeadersInjectAdapter.SETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.IOException;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HttpContext;

public class DelegatingRequestProducer implements HttpAsyncRequestProducer {
  final AgentSpan span;
  final HttpAsyncRequestProducer delegate;

  public DelegatingRequestProducer(final AgentSpan span, final HttpAsyncRequestProducer delegate) {
    this.span = span;
    this.delegate = delegate;
  }

  @Override
  public HttpHost getTarget() {
    return delegate.getTarget();
  }

  @Override
  public HttpRequest generateRequest() throws IOException, HttpException {
    final HttpRequest request = delegate.generateRequest();
    DECORATE.onRequest(span, new HostAndRequestAsHttpUriRequest(delegate.getTarget(), request));

    propagate().inject(span, request, SETTER);
    propagate()
        .injectPathwayContext(span, request, SETTER, HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);

    return request;
  }

  @Override
  public void produceContent(final ContentEncoder encoder, final IOControl ioctrl)
      throws IOException {
    delegate.produceContent(encoder, ioctrl);
  }

  @Override
  public void requestCompleted(final HttpContext context) {
    delegate.requestCompleted(context);
  }

  @Override
  public void failed(final Exception ex) {
    delegate.failed(ex);
  }

  @Override
  public boolean isRepeatable() {
    return delegate.isRepeatable();
  }

  @Override
  public void resetRequest() throws IOException {
    delegate.resetRequest();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }
}
