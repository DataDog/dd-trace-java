package datadog.http.client.okhttp;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.common.socket.NamedPipeSocketFactory;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestListener;
import datadog.http.client.HttpResponse;
import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nullable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.Request;
import okhttp3.Response;

/**
 * This class in an implementation of {@link HttpClient} based on OkHttp 3, compatible with Java 8+.
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
  public CompletableFuture<HttpResponse> executeAsync(HttpRequest request) {
    Objects.requireNonNull(request, "request");
    if (!(request instanceof OkHttpRequest)) {
      throw new IllegalArgumentException("HttpRequest must be OkHttpRequest implementation");
    }

    okhttp3.Request okRequest = ((OkHttpRequest) request).unwrap();
    CompletableFuture<HttpResponse> future = new CompletableFuture<>();
    // Note: OkHttpListener (via EventListener) already handles HttpRequestListener callbacks
    this.delegate
        .newCall(okRequest)
        .enqueue(
            new Callback() {
              @Override
              public void onResponse(Call call, Response response) {
                future.complete(OkHttpResponse.wrap(response));
              }

              @Override
              public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
              }
            });
    return future;
  }

  /**
   * This class bridges OkHttp's {@link EventListener} with the {@link HttpRequestListener}
   * interface.
   *
   * <p>The listener is attached to OkHttp requests as a tag and retrieved during call execution to
   * track request start, completion, and failure events. Response headers are captured during the
   * call lifecycle and provided to the listener upon successful completion.
   */
  static class OkHttpListener extends EventListener {
    private final HttpRequestListener listener;
    private Response response;

    static EventListener fetch(Call call) {
      OkHttpListener listener = call.request().tag(OkHttpListener.class);
      return listener == null ? NONE : listener;
    }

    static @Nullable OkHttpListener wrap(@Nullable HttpRequestListener listener) {
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

  /** Builder for {@link OkHttpClient} instances. */
  public static final class Builder implements HttpClient.Builder {
    private final okhttp3.OkHttpClient.Builder delegate;

    public Builder() {
      this.delegate = new okhttp3.OkHttpClient.Builder();
      // Prevent async call by default, unless a custom executor is provided
      // TODO Do we want to keep this behavior?
      // executor(RejectingExecutorService.INSTANCE);
    }

    @Override
    public HttpClient.Builder connectTimeout(Duration timeout) {
      // We can't use overloaded timeout methods with Duration
      // as instrumentation tests are injecting an old OkHttp3 version
      // where such methods were not introduced
      long timeoutMillis = timeout.toMillis();
      this.delegate
          .connectTimeout(timeoutMillis, MILLISECONDS)
          .readTimeout(timeoutMillis, MILLISECONDS)
          .writeTimeout(timeoutMillis, MILLISECONDS);
      return this;
    }

    @Override
    public HttpClient.Builder proxy(Proxy proxy) {
      this.delegate.proxy(proxy);
      return this;
    }

    @Override
    public HttpClient.Builder proxyAuthenticator(String username, @Nullable String password) {
      this.delegate.proxyAuthenticator(
          (route, response) -> {
            String credential = Credentials.basic(username, password == null ? "" : password);
            return response
                .request()
                .newBuilder()
                .header("Proxy-Authorization", credential)
                .build();
          });
      return this;
    }

    @Override
    public HttpClient.Builder unixDomainSocket(File socketFile) {
      this.delegate.socketFactory(new UnixDomainSocketFactory(socketFile));
      return this;
    }

    @Override
    public HttpClient.Builder namedPipe(String pipeName) {
      this.delegate.socketFactory(new NamedPipeSocketFactory(pipeName));
      return this;
    }

    @Override
    public HttpClient.Builder clearText(boolean clearText) {
      this.delegate.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
      return this;
    }

    @Override
    public HttpClient.Builder executor(Executor executor) {
      // OkHttp Dispatcher requires ExecutorService
      // If the executor is already an ExecutorService, use it directly
      // Otherwise, we'll set up the dispatcher with default executor
      // and note this limitation in documentation
      if (executor instanceof ExecutorService) {
        Dispatcher dispatcher = new Dispatcher((ExecutorService) executor);
        this.delegate.dispatcher(dispatcher);
      }
      // If not an ExecutorService, we can't configure it directly in OkHttp
      return this;
    }

    @Override
    public HttpClient build() {
      okhttp3.OkHttpClient okHttpClient =
          this.delegate.eventListenerFactory(OkHttpListener::fetch).build();
      return new OkHttpClient(okHttpClient);
    }
  }
}
