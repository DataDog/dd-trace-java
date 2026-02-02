package datadog.http.client.okhttp;

import datadog.http.client.HttpUrl;
import java.net.URI;
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
   * Parses a URL string into an HttpUrl.
   *
   * @param url the URL string to parse
   * @return the parsed HttpUrl
   * @throws IllegalArgumentException if the URL is malformed
   * @throws NullPointerException if url is null
   */
  public static HttpUrl parse(String url) {
    okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
    if (parsed == null) {
      throw new IllegalArgumentException("Invalid URL: " + url);
    }
    return wrap(parsed);
  }

  /**
   * Creates an HttpUrl from an URI.
   * @param uri the URI to get an HttpUrl from
   * @return the HttpUrl related to the URI
   */
  public static HttpUrl from(URI uri) {
    return wrap(okhttp3.HttpUrl.get(uri));
  }


  /**
   * Wraps an okhttp3.HttpUrl.
   *
   * @param httpUrl the HttpUrl to wrap
   * @return wrapped HttpUrl
   */
  static HttpUrl wrap(okhttp3.HttpUrl httpUrl) {
    if (httpUrl == null) {
      return null;
    }
    return new OkHttpUrl(httpUrl);
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
  public HttpUrl.Builder newBuilder() {
    return new Builder(delegate.newBuilder());
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
  public static final class Builder implements HttpUrl.Builder {

    private final okhttp3.HttpUrl.Builder delegate;

    Builder(okhttp3.HttpUrl.Builder delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public Builder() {
      this.delegate = new okhttp3.HttpUrl.Builder();
      this.delegate.scheme("http");
    }

    @Override
    public HttpUrl.Builder scheme(String scheme) {
      this.delegate.scheme(scheme);
      return this;
    }

    @Override
    public HttpUrl.Builder host(String host) {
      this.delegate.host(host);
      return this;
    }

    @Override
    public HttpUrl.Builder port(int port) {
      this.delegate.port(port);
      return this;
    }

    @Override
    public HttpUrl.Builder addPathSegment(String segment) {
      this.delegate.addPathSegment(segment);
      return this;
    }

    @Override
    public HttpUrl.Builder addQueryParameter(String name, String value) {
      this.delegate.addQueryParameter(name, value);
      return this;
    }

    @Override
    public HttpUrl build() {
      return OkHttpUrl.wrap(this.delegate.build());
    }
  }
}
