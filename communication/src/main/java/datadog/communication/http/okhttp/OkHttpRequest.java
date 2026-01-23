package datadog.communication.http.okhttp;

import datadog.communication.http.client.HttpRequest;
import datadog.communication.http.client.HttpRequestBody;
import datadog.communication.http.client.HttpUrl;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import okhttp3.Request;

/**
 * OkHttp-based implementation of HttpRequest that wraps okhttp3.Request.
 */
public final class OkHttpRequest implements HttpRequest {

  private final Request delegate;

  private OkHttpRequest(Request delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Wraps an okhttp3.Request.
   *
   * @param okHttpRequest the OkHttp Request to wrap
   * @return wrapped HttpRequest
   */
  public static HttpRequest wrap(Request okHttpRequest) {
    if (okHttpRequest == null) {
      return null;
    }
    return new OkHttpRequest(okHttpRequest);
  }

  /**
   * Unwraps to get the underlying okhttp3.Request.
   *
   * @return the underlying okhttp3.Request
   */
  public Request unwrap() {
    return delegate;
  }

  @Override
  public HttpUrl url() {
    return OkHttpUrl.wrap(delegate.url());
  }

  @Override
  public String method() {
    return delegate.method();
  }

  @Override
  @Nullable
  public String header(String name) {
    return delegate.header(name);
  }

  @Override
  public List<String> headers(String name) {
    return delegate.headers(name);
  }

  @Override
  @Nullable
  public HttpRequestBody body() {
    okhttp3.RequestBody body = delegate.body();
    if (body == null) {
      return null;
    }
    return OkHttpRequestBody.wrap(body);
  }

  @Override
  @Nullable
  public <T> T tag(Class<? extends T> type) {
    return delegate.tag(type);
  }

  /**
   * Builder for OkHttpRequest.
   */
  public static final class OkHttpRequestBuilder implements HttpRequest.Builder {

    private final Request.Builder delegate;

    public OkHttpRequestBuilder() {
      this.delegate = new Request.Builder();
    }

    @Override
    public Builder url(HttpUrl url) {
      Objects.requireNonNull(url, "url");
      if (!(url instanceof OkHttpUrl)) {
        throw new IllegalArgumentException("HttpUrl must be OkHttpUrl implementation");
      }
      delegate.url(((OkHttpUrl) url).unwrap());
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
      delegate.get();
      return this;
    }

    @Override
    public Builder post(HttpRequestBody body) {
      Objects.requireNonNull(body, "body");
      if (!(body instanceof OkHttpRequestBody)) {
        throw new IllegalArgumentException("HttpRequestBody must be OkHttpRequestBody implementation");
      }
      delegate.post(((OkHttpRequestBody) body).unwrap());
      return this;
    }

    @Override
    public Builder put(HttpRequestBody body) {
      Objects.requireNonNull(body, "body");
      if (!(body instanceof OkHttpRequestBody)) {
        throw new IllegalArgumentException("HttpRequestBody must be OkHttpRequestBody implementation");
      }
      delegate.put(((OkHttpRequestBody) body).unwrap());
      return this;
    }

    @Override
    public Builder header(String name, String value) {
      delegate.header(name, value);
      return this;
    }

    @Override
    public Builder addHeader(String name, String value) {
      delegate.addHeader(name, value);
      return this;
    }

    @Override
    public <T> Builder tag(Class<? super T> type, @Nullable T tag) {
      delegate.tag(type, tag);
      return this;
    }

    @Override
    public HttpRequest build() {
      return new OkHttpRequest(delegate.build());
    }
  }
}
