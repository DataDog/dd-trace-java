package datadog.trace.instrumentation.apachehttpclient5;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpContext;

public class DelegatingRequestProducer implements AsyncRequestProducer {
  AgentSpan span;
  final AsyncRequestProducer delegate;
  boolean injectContext = false;

  public DelegatingRequestProducer(final AsyncRequestProducer delegate) {
    this.delegate = delegate;
  }

  public void setSpan(AgentSpan span) {
    this.span = span;
  }

  public void setInjectContext(boolean injectContext) {
    this.injectContext = injectContext;
  }

  @Override
  public void failed(final Exception ex) {
    delegate.failed(ex);
  }

  @Override
  public void sendRequest(RequestChannel channel, HttpContext context)
      throws HttpException, IOException {
    DelegatingRequestChannel requestChannel =
        new DelegatingRequestChannel(channel, span, injectContext);
    delegate.sendRequest(requestChannel, context);
  }

  @Override
  public boolean isRepeatable() {
    return delegate.isRepeatable();
  }

  @Override
  public int available() {
    return delegate.available();
  }

  @Override
  public void produce(DataStreamChannel channel) throws IOException {
    delegate.produce(channel);
  }

  @Override
  public void releaseResources() {
    delegate.releaseResources();
  }
}
