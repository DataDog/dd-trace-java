package datadog.communication.http.jdk;

import datadog.communication.http.client.HttpRequest;
import datadog.communication.http.client.HttpRequestBody;
import datadog.communication.http.client.HttpUrl;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * JDK HttpClient-based implementation of HttpRequest.
 */
public final class JdkHttpRequest implements HttpRequest {

  private final java.net.http.HttpRequest delegate;

  private JdkHttpRequest(java.net.http.HttpRequest delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Wraps a java.net.http.HttpRequest.
   *
   * @param jdkRequest the JDK HttpRequest to wrap
   * @return wrapped HttpRequest
   */
  public static HttpRequest wrap(java.net.http.HttpRequest jdkRequest) {
    if (jdkRequest == null) {
      return null;
    }
    return new JdkHttpRequest(jdkRequest);
  }

  /**
   * Unwraps to get the underlying java.net.http.HttpRequest.
   *
   * @return the underlying java.net.http.HttpRequest
   */
  public java.net.http.HttpRequest unwrap() {
    return delegate;
  }

  @Override
  public HttpUrl url() {
    return JdkHttpUrl.wrap(delegate.uri());
  }

  @Override
  public String method() {
    return delegate.method();
  }

  @Override
  public String header(String name) {
    return delegate.headers().firstValue(name).orElse(null);
  }

  @Override
  public List<String> headers(String name) {
    return delegate.headers().allValues(name);
  }

  @Override
  public HttpRequestBody body() {
    // JDK HttpRequest doesn't provide access to the BodyPublisher content
    // This is a limitation of the JDK API
    return null;
  }

  /**
   * Builder for JdkHttpRequest.
   */
  public static final class JdkHttpRequestBuilder implements HttpRequest.Builder {

    private final java.net.http.HttpRequest.Builder delegate;
    private URI uri;

    public JdkHttpRequestBuilder() {
      this.delegate = java.net.http.HttpRequest.newBuilder();
    }

    @Override
    public Builder url(HttpUrl url) {
      Objects.requireNonNull(url, "url");
      if (!(url instanceof JdkHttpUrl)) {
        throw new IllegalArgumentException("HttpUrl must be JdkHttpUrl implementation");
      }
      this.uri = ((JdkHttpUrl) url).unwrap();
      delegate.uri(this.uri);
      return this;
    }

    @Override
    public Builder url(String url) {
      Objects.requireNonNull(url, "url");
      HttpUrl httpUrl = HttpUrl.parse(url);
      return url(httpUrl);
    }

    @Override
    public Builder get() {
      delegate.GET();
      return this;
    }

    @Override
    public Builder post(HttpRequestBody body) {
      Objects.requireNonNull(body, "body");
      try {
        if (!(body instanceof JdkHttpRequestBody)) {
          // Wrap generic HttpRequestBody
          JdkHttpRequestBody jdkBody = JdkHttpRequestBody.wrap(body);
          delegate.POST(jdkBody.unwrap());
        } else {
          delegate.POST(((JdkHttpRequestBody) body).unwrap());
        }
      } catch (IOException e) {
        throw new RuntimeException("Failed to create request body", e);
      }
      return this;
    }

    @Override
    public Builder put(HttpRequestBody body) {
      Objects.requireNonNull(body, "body");
      try {
        if (!(body instanceof JdkHttpRequestBody)) {
          // Wrap generic HttpRequestBody
          JdkHttpRequestBody jdkBody = JdkHttpRequestBody.wrap(body);
          delegate.PUT(jdkBody.unwrap());
        } else {
          delegate.PUT(((JdkHttpRequestBody) body).unwrap());
        }
      } catch (IOException e) {
        throw new RuntimeException("Failed to create request body", e);
      }
      return this;
    }

    @Override
    public Builder header(String name, String value) {
      delegate.setHeader(name, value);
      return this;
    }

    @Override
    public Builder addHeader(String name, String value) {
      delegate.header(name, value);
      return this;
    }

    @Override
    public HttpRequest build() {
      return new JdkHttpRequest(delegate.build());
    }
  }
}
