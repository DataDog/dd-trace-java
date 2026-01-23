package datadog.communication.http.okhttp;

import datadog.communication.http.client.HttpUrl;
import java.util.Objects;

/**
 * OkHttp-based implementation of HttpUrl that wraps okhttp3.HttpUrl.
 */
public final class OkHttpUrl implements HttpUrl {

  private final okhttp3.HttpUrl delegate;

  private OkHttpUrl(okhttp3.HttpUrl delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Wraps an okhttp3.HttpUrl.
   *
   * @param okHttpUrl the OkHttp URL to wrap
   * @return wrapped HttpUrl
   */
  public static HttpUrl wrap(okhttp3.HttpUrl okHttpUrl) {
    if (okHttpUrl == null) {
      return null;
    }
    return new OkHttpUrl(okHttpUrl);
  }

  /**
   * Unwraps to get the underlying okhttp3.HttpUrl.
   *
   * @return the underlying okhttp3.HttpUrl
   */
  public okhttp3.HttpUrl unwrap() {
    return delegate;
  }

  @Override
  public String url() {
    return delegate.toString();
  }

  @Override
  public String scheme() {
    return delegate.scheme();
  }

  @Override
  public String host() {
    return delegate.host();
  }

  @Override
  public int port() {
    return delegate.port();
  }

  @Override
  public HttpUrl resolve(String path) {
    okhttp3.HttpUrl resolved = delegate.resolve(path);
    return wrap(resolved);
  }

  @Override
  public Builder newBuilder() {
    return new OkHttpUrlBuilder(delegate.newBuilder());
  }

  @Override
  public String toString() {
    return url();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    OkHttpUrl that = (OkHttpUrl) o;
    return delegate.equals(that.delegate);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }

  /**
   * Builder for OkHttpUrl.
   */
  public static final class OkHttpUrlBuilder implements HttpUrl.Builder {

    private final okhttp3.HttpUrl.Builder delegate;

    OkHttpUrlBuilder(okhttp3.HttpUrl.Builder delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public OkHttpUrlBuilder() {
      this.delegate = new okhttp3.HttpUrl.Builder();
    }

    @Override
    public Builder scheme(String scheme) {
      delegate.scheme(scheme);
      return this;
    }

    @Override
    public Builder host(String host) {
      delegate.host(host);
      return this;
    }

    @Override
    public Builder port(int port) {
      delegate.port(port);
      return this;
    }

    @Override
    public Builder addPathSegment(String segment) {
      delegate.addPathSegment(segment);
      return this;
    }

    @Override
    public HttpUrl build() {
      return OkHttpUrl.wrap(delegate.build());
    }
  }
}
