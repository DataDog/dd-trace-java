package datadog.http.client.jdk;

import datadog.http.client.HttpResponse;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * JDK HttpClient-based implementation of HttpResponse.
 */
public final class JdkHttpResponse implements HttpResponse {
  private final java.net.http.HttpResponse<InputStream> delegate;

  private JdkHttpResponse(java.net.http.HttpResponse<InputStream> delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Wraps a java.net.http.HttpResponse.
   *
   * @param jdkResponse the JDK HttpResponse to wrap
   * @return wrapped HttpResponse
   */
  static HttpResponse wrap(java.net.http.HttpResponse<InputStream> jdkResponse) {
    if (jdkResponse == null) {
      return null;
    }
    return new JdkHttpResponse(jdkResponse);
  }

  /**
   * Unwraps to get the underlying java.net.http.HttpResponse.
   *
   * @return the underlying java.net.http.HttpResponse
   */
  java.net.http.HttpResponse<InputStream> unwrap() {
    return delegate;
  }

  @Override
  public int code() {
    return delegate.statusCode();
  }

  @Override
  public boolean isSuccessful() {
    int code = delegate.statusCode();
    return code >= 200 && code < 300;
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
  public InputStream body() {
    return delegate.body();
  }

  @Override
  public void close() {
    // TODO Need review. Unclear if needed
    // JDK HttpResponse doesn't require explicit closing
    // The InputStream will be closed by the caller
    try {
      InputStream body = delegate.body();
      if (body != null) {
        body.close();
      }
    } catch (Exception e) {
      // Ignore exceptions during close
    }
  }
}
