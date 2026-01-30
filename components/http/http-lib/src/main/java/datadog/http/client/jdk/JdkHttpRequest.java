package datadog.http.client.jdk;

import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpRequestListener;
import datadog.http.client.HttpUrl;
import java.net.URI;
import java.util.List;

/**
 * JDK HttpClient-based implementation of HttpRequest.
 */
public final class JdkHttpRequest implements HttpRequest {
  final java.net.http.HttpRequest delegate;
  /** The request listener, {@code null} if no listener is set. */
  HttpRequestListener listener;

  private JdkHttpRequest(java.net.http.HttpRequest delegate, HttpRequestListener listener) {
    this.delegate = delegate;
    this.listener = listener;
  }

  // TODO Remove if not used
  /**
   * Wraps a java.net.http.HttpRequest.
   *
   * @param jdkRequest the JDK HttpRequest to wrap
   * @return wrapped HttpRequest
   */
  static HttpRequest wrap(java.net.http.HttpRequest jdkRequest) {
    if (jdkRequest == null) {
      return null;
    }
    return new JdkHttpRequest(jdkRequest, null);
  }

  /**
   * Unwraps to get the underlying java.net.http.HttpRequest.
   *
   * @return the underlying java.net.http.HttpRequest
   */
  java.net.http.HttpRequest unwrap() {
    return this.delegate;
  }

  @Override
  public HttpUrl url() {
    return JdkHttpUrl.wrap(this.delegate.uri());
  }

  @Override
  public String method() {
    return this.delegate.method();
  }

  @Override
  public String header(String name) {
    return this.delegate.headers().firstValue(name).orElse(null);
  }

  @Override
  public List<String> headers(String name) {
    return this.delegate.headers().allValues(name);
  }

  // @Override
  // public HttpRequestBody body() {
  //   // JDK HttpRequest doesn't provide access to the BodyPublisher content
  //   // This is a limitation of the JDK API
  //   return null;
  // }

  /**
   * Builder for JdkHttpRequest.
   */
  public static final class Builder implements HttpRequest.Builder {
    private final java.net.http.HttpRequest.Builder delegate;
    private HttpRequestListener listener;

    public Builder() {
      this.delegate = java.net.http.HttpRequest.newBuilder();
    }

    @Override
    public HttpRequest.Builder url(HttpUrl url) {
      if (!(url instanceof JdkHttpUrl)) {
        throw new IllegalArgumentException("HttpUrl must be JdkHttpUrl implementation");
      }
      URI uri = ((JdkHttpUrl) url).unwrap();
      this.delegate.uri(uri);
      return this;
    }

    @Override
    public HttpRequest.Builder url(String url) {
      this.delegate.uri(URI.create(url));
      return this;
    }

    @Override
    public HttpRequest.Builder get() {
      this.delegate.GET();
      return this;
    }

    @Override
    public HttpRequest.Builder post(HttpRequestBody body) {
      if (!(body instanceof JdkHttpRequestBody)) {
        throw new IllegalArgumentException("HttpRequestBody must be JdkHttpRequestBody implementation");
      }
      java.net.http.HttpRequest.BodyPublisher bodyPublisher = ((JdkHttpRequestBody) body).publisher();
      this.delegate.POST(bodyPublisher);
      return this;
    }

    @Override
    public HttpRequest.Builder put(HttpRequestBody body) {
      if (!(body instanceof JdkHttpRequestBody)) {
        throw new IllegalArgumentException("HttpRequestBody must be JdkHttpRequestBody implementation");
      }
      this.delegate.PUT(((JdkHttpRequestBody) body).publisher());
      return this;
    }

    @Override
    public HttpRequest.Builder header(String name, String value) {
      this.delegate.setHeader(name, value);
      return this;
    }

    @Override
    public HttpRequest.Builder addHeader(String name, String value) {
      this.delegate.header(name, value);
      return this;
    }

    @Override
    public HttpRequest.Builder listener(HttpRequestListener listener) {
      this.listener = listener;
      return this;
    }

    @Override
    public HttpRequest build() {
      return new JdkHttpRequest(this.delegate.build(), this.listener);
    }
  }
}
