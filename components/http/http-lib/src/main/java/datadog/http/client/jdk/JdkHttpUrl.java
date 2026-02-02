package datadog.http.client.jdk;

import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.http.client.HttpUrl;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/** JDK-based implementation of HttpUrl that wraps java.net.URI. */
public final class JdkHttpUrl implements HttpUrl {

  private final URI delegate;

  private JdkHttpUrl(URI delegate) {
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
    return wrap(URI.create(url));
  }

  /**
   * Creates an HttpUrl from an URI.
   *
   * @param uri the URI to get an HttpUrl from
   * @return the HttpUrl related to the URI
   */
  public static HttpUrl from(URI uri) {
    return wrap(uri);
  }

  /**
   * Wraps a java.net.URI.
   *
   * @param uri the URI to wrap
   * @return wrapped HttpUrl
   */
  static HttpUrl wrap(URI uri) {
    if (uri == null) {
      return null;
    }
    return new JdkHttpUrl(uri);
  }

  /**
   * Unwraps to get the underlying java.net.URI.
   *
   * @return the underlying java.net.URI
   */
  public URI unwrap() {
    return delegate;
  }

  @Override
  public String url() {
    return delegate.toString();
  }

  @Override
  public String scheme() {
    return delegate.getScheme();
  }

  @Override
  public String host() {
    return delegate.getHost();
  }

  @Override
  public int port() {
    int port = delegate.getPort();
    if (port == -1) {
      // Return default port for scheme
      String scheme = delegate.getScheme();
      if ("https".equalsIgnoreCase(scheme)) {
        return 443;
      } else if ("http".equalsIgnoreCase(scheme)) {
        return 80;
      }
    }
    return port;
  }

  @Override
  public HttpUrl resolve(String path) {
    URI resolved = delegate.resolve(path);
    return wrap(resolved);
  }

  @Override
  public HttpUrl.Builder newBuilder() {
    return new Builder(delegate);
  }

  @Override
  public String toString() {
    return url();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    JdkHttpUrl that = (JdkHttpUrl) o;
    return delegate.equals(that.delegate);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }

  /** Builder for JdkHttpUrl. */
  public static final class Builder implements HttpUrl.Builder {
    private final StringBuilder query = new StringBuilder();
    private final StringBuilder path = new StringBuilder();
    private String scheme;
    private String host;
    private int port = -1;

    Builder(URI baseUri) {
      this.scheme = baseUri.getScheme();
      this.host = baseUri.getHost();
      this.port = baseUri.getPort();
      if (baseUri.getPath() != null) {
        this.path.append(baseUri.getPath());
      }
      if (baseUri.getQuery() != null) {
        this.query.append(baseUri.getQuery());
      }
    }

    public Builder() {
      this.scheme = "http";
    }

    @Override
    public HttpUrl.Builder scheme(String scheme) {
      this.scheme = Objects.requireNonNull(scheme, "scheme");
      return this;
    }

    @Override
    public HttpUrl.Builder host(String host) {
      this.host = Objects.requireNonNull(host, "host");
      return this;
    }

    @Override
    public HttpUrl.Builder port(int port) {
      if (port < 0 || port > 65535) {
        throw new IllegalArgumentException("Invalid port: " + port);
      }
      this.port = port;
      return this;
    }

    @Override
    public HttpUrl.Builder addPathSegment(String segment) {
      if (segment == null || segment.isEmpty()) {
        return this;
      }
      if (path.length() == 0 || path.charAt(path.length() - 1) != '/') {
        path.append('/');
      }
      path.append(segment);
      return this;
    }

    @Override
    public HttpUrl.Builder addQueryParameter(String name, String value) {
      if (name == null || name.isEmpty()) {
        return this;
      }
      if (query.length() > 0) {
        query.append('&');
      }
      query.append(encode(name, UTF_8));
      if (value != null) {
        query.append('=').append(encode(value, UTF_8));
      }
      return this;
    }

    @Override
    public HttpUrl build() {
      try {
        StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(scheme).append("://").append(host);
        if (port != -1) {
          uriBuilder.append(':').append(port);
        }
        uriBuilder.append(path);
        if (query.length() > 0) {
          uriBuilder.append('?').append(query);
        }
        URI uri = new URI(uriBuilder.toString());
        return JdkHttpUrl.wrap(uri);
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException("Invalid URL", e);
      }
    }
  }
}
