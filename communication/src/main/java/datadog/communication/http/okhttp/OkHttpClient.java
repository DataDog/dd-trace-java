package datadog.communication.http.okhttp;

import datadog.communication.http.client.HttpClient;
import datadog.communication.http.client.HttpRequest;
import datadog.communication.http.client.HttpResponse;
import java.io.IOException;
import java.util.Objects;
import okhttp3.Call;
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

    public OkHttpClientBuilder() {
      this.delegate = new okhttp3.OkHttpClient.Builder();
    }

    OkHttpClientBuilder(okhttp3.OkHttpClient.Builder delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public HttpClient build() {
      return new OkHttpClient(delegate.build());
    }
  }
}
