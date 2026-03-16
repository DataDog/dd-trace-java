package datadog.http.client.okhttp;

import datadog.http.client.HttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import okhttp3.ResponseBody;

/** This class implements {@link HttpResponse} using OkHttp 3. */
public final class OkHttpResponse implements HttpResponse {
  private final okhttp3.Response delegate;

  private OkHttpResponse(okhttp3.Response delegate) {
    this.delegate = delegate;
  }

  static @Nullable HttpResponse wrap(@Nullable okhttp3.Response okResponse) {
    if (okResponse == null) {
      return null;
    }
    return new OkHttpResponse(okResponse);
  }

  // TODO Remove if not used
  okhttp3.Response unwrap() {
    return this.delegate;
  }

  @Override
  public int code() {
    return this.delegate.code();
  }

  @Override
  public boolean isSuccessful() {
    return this.delegate.isSuccessful();
  }

  @Override
  public String header(String name) {
    // OkHttp's header() returns the last value, but the HTTP API expects the first
    List<String> values = this.delegate.headers(name);
    return values.isEmpty() ? null : values.get(0);
  }

  @Override
  public List<String> headers(String name) {
    return this.delegate.headers(name);
  }

  @Override
  public Set<String> headerNames() {
    return this.delegate.headers().names();
  }

  @Override
  public InputStream body() {
    okhttp3.ResponseBody body = this.delegate.body();
    if (body == null) {
      return null;
    }
    return body.byteStream();
  }

  @Override
  public String bodyAsString() throws IOException {
    ResponseBody body = this.delegate.body();
    return body == null ? "" : body.string();
  }
}
