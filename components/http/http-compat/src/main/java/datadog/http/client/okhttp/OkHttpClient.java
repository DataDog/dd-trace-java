package datadog.http.client.okhttp;

import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestListener;
import datadog.http.client.HttpResponse;
import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttp-based implementation that wraps okhttp3.OkHttpClient.
 * Compatible with Java 8+.
 */
public final class OkHttpClient implements HttpClient {
  private final okhttp3.OkHttpClient delegate;

  private OkHttpClient(okhttp3.OkHttpClient delegate) {
    this.delegate = delegate;
  }

  @Override
  public HttpResponse execute(HttpRequest request) throws IOException {
    Objects.requireNonNull(request, "request");

    if (!(request instanceof OkHttpRequest)) {
      throw new IllegalArgumentException("HttpRequest must be OkHttpRequest implementation");
    }

    okhttp3.Request okRequest = ((OkHttpRequest) request).unwrap();

    okhttp3.Response okResponse = this.delegate.newCall(okRequest).execute();
    return OkHttpResponse.wrap(okResponse);
  }

  @Override
  public void close() {
    this.delegate.dispatcher().executorService().shutdown();
    this.delegate.connectionPool().evictAll();
  }

  static class OkHttpListener extends EventListener {
    private final HttpRequestListener listener;
    private Response response;


    static OkHttpListener fetch(Call call) {
      return call.request().tag(OkHttpListener.class);
    }

    static OkHttpListener wrap(HttpRequestListener listener) {
      if (listener == null) {
        return null;
      }
      return new OkHttpListener(listener);
    }

    private OkHttpListener(HttpRequestListener listener) {
      this.listener = listener;
    }

    @Override
    public void callStart(Call call) {
      Request request = call.request();
      this.listener.onRequestStart(OkHttpRequest.wrap(request));
    }

    @Override
    public void responseHeadersEnd(Call call, Response response) {
      this.response = response;
    }

    @Override
    public void callEnd(Call call) {
      Request request = call.request();
      this.listener.onRequestEnd(OkHttpRequest.wrap(request), OkHttpResponse.wrap(this.response));
    }

    @Override
    public void callFailed(Call call, IOException ioe) {
      Request request = call.request();
      this.listener.onRequestFailure(OkHttpRequest.wrap(request), ioe);
    }
  }

  /**
   * Builder for OkHttpClient.
   */
  public static final class Builder implements HttpClient.Builder {

    private final okhttp3.OkHttpClient.Builder delegate;
    private HttpRequestListener eventListener;

    public Builder() {
      this.delegate = new okhttp3.OkHttpClient.Builder();
    }

    @Override
    public HttpClient.Builder connectTimeout(long timeout, TimeUnit unit) {
      delegate.connectTimeout(timeout, unit);
      return this;
    }

    @Override
    public HttpClient.Builder readTimeout(long timeout, TimeUnit unit) {
      delegate.readTimeout(timeout, unit);
      return this;
    }

    @Override
    public HttpClient.Builder writeTimeout(long timeout, TimeUnit unit) {
      delegate.writeTimeout(timeout, unit);
      return this;
    }

    @Override
    public HttpClient.Builder proxy(Proxy proxy) {
      delegate.proxy(proxy);
      return this;
    }

    @Override
    public HttpClient.Builder proxyAuthenticator(String username, String password) {
      delegate.proxyAuthenticator((route, response) -> {
        String credential = Credentials.basic(username, password == null ? "" : password);
        return response.request().newBuilder()
            .header("Proxy-Authorization", credential)
            .build();
      });
      return this;
    }

    @Override
    public HttpClient.Builder unixDomainSocket(File socketFile) {
      // Unix domain socket support requires custom SocketFactory
      // Will be implemented in future phase
      return this;
    }

    @Override
    public HttpClient.Builder namedPipe(String pipeName) {
      // Named pipe support is Windows-specific
      // Will be implemented in future phase
      return this;
    }

    @Override
    public HttpClient.Builder clearText(boolean clearText) {
      // OkHttp supports both HTTP and HTTPS by default
      // No special configuration needed for clear text
      return this;
    }

    @Override
    public HttpClient.Builder retryOnConnectionFailure(boolean retry) {
      delegate.retryOnConnectionFailure(retry);
      return this;
    }

    @Override
    public HttpClient.Builder maxRequests(int maxRequests) {
      Dispatcher dispatcher = new Dispatcher();
      dispatcher.setMaxRequests(maxRequests);
      delegate.dispatcher(dispatcher);
      return this;
    }

    @Override
    public HttpClient.Builder dispatcher(Executor executor) {
      // OkHttp Dispatcher requires ExecutorService
      // If the executor is already an ExecutorService, use it directly
      // Otherwise, we'll set up the dispatcher with default executor
      // and note this limitation in documentation
      if (executor instanceof java.util.concurrent.ExecutorService) {
        Dispatcher dispatcher = new Dispatcher((java.util.concurrent.ExecutorService) executor);
        delegate.dispatcher(dispatcher);
      }
      // If not an ExecutorService, we can't configure it directly in OkHttp
      return this;
    }

    @Override
    public HttpClient build() {
      okhttp3.OkHttpClient okHttpClient = this.delegate.eventListenerFactory(OkHttpListener::fetch).build();
      return new OkHttpClient(okHttpClient);
    }
  }
}
