package datadog.http.client.jdk;

import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpRequestListener;
import datadog.http.client.HttpUrl;
import java.net.URI;
import java.util.List;

/** JDK HttpClient-based implementation of HttpRequest. */
public final class JdkHttpRequest implements HttpRequest {
  final java.net.http.HttpRequest delegate;

  /** The request body, {@code null} if no body is set. */
  private final HttpRequestBody body;

  /** The request listener, {@code null} if no listener is set. */
  HttpRequestListener listener;

  private JdkHttpRequest(
      java.net.http.HttpRequest delegate, HttpRequestBody body, HttpRequestListener listener) {
    this.delegate = delegate;
    this.body = body;
    this.listener = listener;
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

  @Override
  public HttpRequestBody body() {
    return this.body;
  }

  /** Builder for JdkHttpRequest. */
  public static final class Builder implements HttpRequest.Builder {
    private final java.net.http.HttpRequest.Builder delegate;
    private HttpRequestBody body;
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
      this.delegate.uri(normalizeUri(uri));
      return this;
    }

    /**
     * Normalizes a URI to work around Java HttpClient bugs.
     *
     * <p>Java 11-19 HttpClient has a bug where it sends malformed HTTP/1.1 request lines with
     * "relative path with authority" format that violates the HTTP/1.1 specification. This causes
     * servers (like Jetty) to reject requests with: {@code IllegalArgumentException: Relative path
     * with authority}
     *
     * <p>This bug was fixed in Java 20 (JDK-8272702), but the workaround is applied unconditionally
     * for simplicity and to ensure robust behavior across all Java versions.
     *
     * <p>The fix explicitly reconstructs the URI with proper components to ensure correct
     * formatting before passing to the JDK HttpClient.
     *
     * @param uri the URI to normalize
     * @return normalized URI with properly separated components, or original URI if already correct
     */
    private URI normalizeUri(URI uri) {
      String authority = uri.getAuthority();
      String path = uri.getPath();
      // Fast path: if path is already correct (starts with / or is null/empty)
      if (authority == null || path == null || path.isEmpty() || path.startsWith("/")) {
        return uri;
      }
      // Ensure path starts with / if we have an authority
      try {
        String correctedPath = "/" + path;
        return new URI(
            uri.getScheme(), authority, correctedPath, uri.getQuery(), uri.getFragment());
      } catch (java.net.URISyntaxException e) {
        // If normalization fails, return the original URI
        return uri;
      }
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
        throw new IllegalArgumentException(
            "HttpRequestBody must be JdkHttpRequestBody implementation");
      }
      this.body = body;
      this.delegate.POST(((JdkHttpRequestBody) body).publisher());
      return this;
    }

    @Override
    public HttpRequest.Builder put(HttpRequestBody body) {
      if (!(body instanceof JdkHttpRequestBody)) {
        throw new IllegalArgumentException(
            "HttpRequestBody must be JdkHttpRequestBody implementation");
      }
      this.body = body;
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
      return new JdkHttpRequest(this.delegate.build(), this.body, this.listener);
    }
  }
}
