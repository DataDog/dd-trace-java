package datadog.smoketest.jbossmodules.client;

import datadog.smoketest.jbossmodules.messaging.ClientSupport;
import java.io.IOException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

public class MessageClient extends ClientSupport {
  private CloseableHttpClient delegate;

  @Override
  protected void doStart() {
    delegate = HttpClients.custom().build();
  }

  @Override
  protected void doStop() throws IOException {
    delegate.close();
    delegate = null;
  }

  @Override
  public HttpParams getParams() {
    return delegate.getParams();
  }

  @Override
  public ClientConnectionManager getConnectionManager() {
    return delegate.getConnectionManager();
  }

  @Override
  public HttpResponse execute(final HttpUriRequest request) throws IOException {
    return delegate.execute(request);
  }

  @Override
  public HttpResponse execute(final HttpUriRequest request, final HttpContext context)
      throws IOException {
    return delegate.execute(request, context);
  }

  @Override
  public HttpResponse execute(final HttpHost target, final HttpRequest request) throws IOException {
    return delegate.execute(target, request);
  }

  @Override
  public HttpResponse execute(
      final HttpHost target, final HttpRequest request, final HttpContext context)
      throws IOException {
    return delegate.execute(target, request, context);
  }

  @Override
  public <T> T execute(
      final HttpUriRequest request, final ResponseHandler<? extends T> responseHandler)
      throws IOException {
    return delegate.execute(request, responseHandler);
  }

  @Override
  public <T> T execute(
      final HttpUriRequest request,
      final ResponseHandler<? extends T> responseHandler,
      final HttpContext context)
      throws IOException {
    return delegate.execute(request, responseHandler, context);
  }

  @Override
  public <T> T execute(
      final HttpHost target,
      final HttpRequest request,
      final ResponseHandler<? extends T> responseHandler)
      throws IOException {
    return delegate.execute(target, request, responseHandler);
  }

  @Override
  public <T> T execute(
      final HttpHost target,
      final HttpRequest request,
      final ResponseHandler<? extends T> responseHandler,
      final HttpContext context)
      throws IOException {
    return delegate.execute(target, request, responseHandler, context);
  }
}
