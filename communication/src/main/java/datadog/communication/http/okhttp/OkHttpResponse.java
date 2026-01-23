package datadog.communication.http.okhttp;

import datadog.communication.http.client.HttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OkHttp-based implementation of HttpResponse that wraps okhttp3.Response.
 */
public final class OkHttpResponse implements HttpResponse {

  private final Response delegate;

  private OkHttpResponse(Response delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Wraps an okhttp3.Response.
   *
   * @param okHttpResponse the OkHttp Response to wrap
   * @return wrapped HttpResponse
   */
  public static HttpResponse wrap(Response okHttpResponse) {
    if (okHttpResponse == null) {
      return null;
    }
    return new OkHttpResponse(okHttpResponse);
  }

  /**
   * Unwraps to get the underlying okhttp3.Response.
   *
   * @return the underlying okhttp3.Response
   */
  public Response unwrap() {
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
    return delegate.header(name);
  }

  @Override
  public List<String> headers(String name) {
    return delegate.headers(name);
  }

  @Override
  public InputStream body() {
    ResponseBody body = delegate.body();
    if (body == null) {
      return null;
    }
    return body.byteStream();
  }

  @Override
  public void close() {
    try {
      delegate.close();
    } catch (RuntimeException e) {
      // Ignore exceptions during close
    }
  }
}
