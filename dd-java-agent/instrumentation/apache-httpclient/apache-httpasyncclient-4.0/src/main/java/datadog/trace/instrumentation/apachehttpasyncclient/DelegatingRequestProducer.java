package datadog.trace.instrumentation.apachehttpasyncclient;

import static datadog.context.Context.current;
import static datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpasyncclient.HttpHeadersInjectAdapter.SETTER;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HttpContext;

public class DelegatingRequestProducer implements HttpAsyncRequestProducer {
  AgentSpan span;
  final HttpAsyncRequestProducer delegate;
  boolean injectContext = false;

  public DelegatingRequestProducer(final HttpAsyncRequestProducer delegate) {
    this.delegate = delegate;
  }

  public void setInjectContext(boolean injectContext) {
    this.injectContext = injectContext;
  }

  public void setSpan(final AgentSpan span) {
    this.span = span;
  }

  @Override
  public HttpHost getTarget() {
    return delegate.getTarget();
  }

  @Override
  public HttpRequest generateRequest() throws IOException, HttpException {
    final HttpRequest request = delegate.generateRequest();
    if (span != null) {
      DECORATE.onRequest(span, new HostAndRequestAsHttpUriRequest(delegate.getTarget(), request));
    }
    if (injectContext) {
      Context receiver = current();
      if (span != null) {
        receiver = receiver.with(span);
      }
      DECORATE.injectContext(receiver, request, SETTER);
    }
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
