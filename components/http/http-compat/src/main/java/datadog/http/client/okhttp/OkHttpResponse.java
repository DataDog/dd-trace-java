package datadog.http.client.okhttp;

import datadog.http.client.HttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import okhttp3.ResponseBody;

/**
 * OkHttp-based implementation of HttpResponse.
 */
public final class OkHttpResponse implements HttpResponse {
  private final okhttp3.Response delegate;

  private OkHttpResponse(okhttp3.Response delegate) {
    this.delegate = delegate;
  }

  /**
   * Wraps an okhttp3.Response.
   *
   * @param okResponse the OkHttp Response to wrap
   * @return wrapped HttpResponse
   */
  static HttpResponse wrap(okhttp3.Response okResponse) {
    if (okResponse == null) {
      return null;
    }
    return new OkHttpResponse(okResponse);
  }

  /**
   * Unwraps to get the underlying okhttp3.Response.
   *
   * @return the underlying okhttp3.Response
   */
  okhttp3.Response unwrap() {
    return delegate;
  }

  @Override
  public int code() {
    return delegate.code();
  }

  @Override
  public boolean isSuccessful() {
    return delegate.isSuccessful();
  }

  @Override
  public String header(String name) {
    // OkHttp's header() returns the LAST value, but our API expects the FIRST
    List<String> values = delegate.headers(name);
    return values.isEmpty() ? null : values.get(0);
  }

  @Override
  public List<String> headers(String name) {
    return delegate.headers(name);
  }

  @Override
  public Set<String> headerNames() {
    return delegate.headers().names();
  }

  @Override
  public InputStream body() {
    okhttp3.ResponseBody body = delegate.body();
    if (body == null) {
      return null;
    }
    return body.byteStream();
  }

  @Override
  public String bodyAsString() throws IOException {
    ResponseBody body = delegate.body();
    return body == null ? "" : body.string();
  }

  @Override
  public void close() {
    okhttp3.ResponseBody body = delegate.body();
    if (body != null) {
      body.close();
    }
  }
}
