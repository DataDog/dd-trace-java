package datadog.communication.http.okhttp;

import datadog.common.socket.NamedPipeSocketFactory;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.communication.http.client.HttpClient;
import datadog.communication.http.client.HttpListener;
import datadog.communication.http.client.HttpRequest;
import datadog.communication.http.client.HttpResponse;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttp-based implementation of HttpClient that wraps okhttp3.OkHttpClient.
 */
public final class OkHttpClient implements HttpClient {

  private final okhttp3.OkHttpClient delegate;

  private OkHttpClient(okhttp3.OkHttpClient delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Wraps an okhttp3.OkHttpClient.
   *
   * @param okHttpClient the OkHttp client to wrap
   * @return wrapped HttpClient
   */
  public static HttpClient wrap(okhttp3.OkHttpClient okHttpClient) {
    if (okHttpClient == null) {
      return null;
    }
    return new OkHttpClient(okHttpClient);
  }

  /**
   * Unwraps to get the underlying okhttp3.OkHttpClient.
   *
   * @return the underlying okhttp3.OkHttpClient
   */
  public okhttp3.OkHttpClient unwrap() {
    return delegate;
  }

  @Override
  public HttpResponse execute(HttpRequest request) throws IOException {
    Objects.requireNonNull(request, "request");

    if (!(request instanceof OkHttpRequest)) {
      throw new IllegalArgumentException("HttpRequest must be OkHttpRequest implementation");
    }

    Request okHttpRequest = ((OkHttpRequest) request).unwrap();
    Call call = delegate.newCall(okHttpRequest);
    Response okHttpResponse = call.execute();

    return OkHttpResponse.wrap(okHttpResponse);
  }

  @Override
  public void close() throws IOException {
    // OkHttp client doesn't require explicit closing in most cases
    // Connection pools are managed internally
    delegate.dispatcher().executorService().shutdown();
    delegate.connectionPool().evictAll();
  }

  /**
   * Builder for OkHttpClient.
   */
  public static final class OkHttpClientBuilder implements HttpClient.Builder {

    private final okhttp3.OkHttpClient.Builder delegate;
    private HttpListener eventListener;
    private Integer maxRequests;

    public OkHttpClientBuilder() {
      this.delegate = new okhttp3.OkHttpClient.Builder();
    }

    OkHttpClientBuilder(okhttp3.OkHttpClient.Builder delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Builder connectTimeout(long timeout, TimeUnit unit) {
      delegate.connectTimeout(timeout, unit);
      return this;
    }

    @Override
    public Builder readTimeout(long timeout, TimeUnit unit) {
      delegate.readTimeout(timeout, unit);
      return this;
    }

    @Override
    public Builder writeTimeout(long timeout, TimeUnit unit) {
      delegate.writeTimeout(timeout, unit);
      return this;
    }

    @Override
    public Builder proxy(Proxy proxy) {
      delegate.proxy(proxy);
      return this;
    }

    @Override
    public Builder proxyAuthenticator(String username, String password) {
      delegate.proxyAuthenticator((route, response) -> {
        String credential = Credentials.basic(username, password == null ? "" : password);
        return response.request()
            .newBuilder()
            .header("Proxy-Authorization", credential)
            .build();
      });
      return this;
    }

    @Override
    public Builder unixDomainSocket(File socketFile) {
      delegate.socketFactory(new UnixDomainSocketFactory(socketFile));
      return this;
    }

    @Override
    public Builder namedPipe(String pipeName) {
      delegate.socketFactory(new NamedPipeSocketFactory(pipeName));
      return this;
    }

    @Override
    public Builder clearText(boolean clearText) {
      if (clearText) {
        delegate.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
      }
      return this;
    }

    @Override
    public Builder retryOnConnectionFailure(boolean retry) {
      delegate.retryOnConnectionFailure(retry);
      return this;
    }

    @Override
    public Builder maxRequests(int maxRequests) {
      this.maxRequests = maxRequests;
      // Configure connection pool to support max requests
      delegate.connectionPool(new ConnectionPool(maxRequests, 1, TimeUnit.SECONDS));
      return this;
    }

    @Override
    public Builder dispatcher(Executor executor) {
      // OkHttp requires ExecutorService, not just Executor
      if (!(executor instanceof java.util.concurrent.ExecutorService)) {
        throw new IllegalArgumentException("Executor must be an ExecutorService for OkHttp");
      }
      Dispatcher dispatcher = new Dispatcher((java.util.concurrent.ExecutorService) executor);
      delegate.dispatcher(dispatcher);
      return this;
    }

    @Override
    public Builder eventListener(HttpListener listener) {
      this.eventListener = listener;
      return this;
    }

    @Override
    public HttpClient build() {
      // Configure event listener if set
      if (eventListener != null) {
        delegate.eventListenerFactory(call -> new OkHttpEventListenerAdapter(eventListener, call.request()));
      }

      okhttp3.OkHttpClient okHttpClient = delegate.build();

      // Apply max requests to dispatcher if configured
      if (maxRequests != null) {
        okHttpClient.dispatcher().setMaxRequests(maxRequests);
        okHttpClient.dispatcher().setMaxRequestsPerHost(maxRequests);
      }

      return new OkHttpClient(okHttpClient);
    }
  }

  /**
   * Adapter to bridge HttpListener to OkHttp's EventListener.
   */
  private static final class OkHttpEventListenerAdapter extends EventListener {

    private final HttpListener delegate;
    private final Request okHttpRequest;

    OkHttpEventListenerAdapter(HttpListener delegate, Request okHttpRequest) {
      this.delegate = delegate;
      this.okHttpRequest = okHttpRequest;
    }

    @Override
    public void callStart(Call call) {
      HttpRequest request = OkHttpRequest.wrap(okHttpRequest);
      delegate.onRequestStart(request);
    }

    @Override
    public void callEnd(Call call) {
      HttpRequest request = OkHttpRequest.wrap(okHttpRequest);
      // Note: response is not available here in OkHttp's EventListener API
      delegate.onRequestEnd(request, null);
    }

    @Override
    public void callFailed(Call call, IOException ioe) {
      HttpRequest request = OkHttpRequest.wrap(okHttpRequest);
      delegate.onRequestFailure(request, ioe);
    }
  }
}
