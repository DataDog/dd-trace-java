package datadog.http.client.okhttp;

import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpRequestListener;
import datadog.http.client.HttpUrl;
import datadog.http.client.okhttp.OkHttpClient.OkHttpListener;
import java.util.List;
import javax.annotation.Nullable;

/** This class implements {@link datadog.http.client.HttpRequest} using OkHttp 3. */
public final class OkHttpRequest implements HttpRequest {
  private final okhttp3.Request delegate;

  private OkHttpRequest(okhttp3.Request delegate) {
    this.delegate = delegate;
  }

  static @Nullable HttpRequest wrap(@Nullable okhttp3.Request okRequest) {
    if (okRequest == null) {
      return null;
    }
    return new OkHttpRequest(okRequest);
  }

  okhttp3.Request unwrap() {
    return this.delegate;
  }

  @Override
  public HttpUrl url() {
    return OkHttpUrl.wrap(this.delegate.url());
  }

  @Override
  public String method() {
    return this.delegate.method();
  }

  @Override
  public String header(String name) {
    return this.delegate.header(name);
  }

  @Override
  public List<String> headers(String name) {
    return this.delegate.headers(name);
  }

  @Override
  public HttpRequestBody body() {
    return OkHttpRequestBody.wrap(this.delegate.body());
  }

  /** This class implements {@link HttpRequest.Builder} using OkHttp 3. */
  public static final class Builder implements HttpRequest.Builder {
    private final okhttp3.Request.Builder delegate;

    public Builder() {
      this.delegate = new okhttp3.Request.Builder();
    }

    @Override
    public HttpRequest.Builder url(HttpUrl url) {
      if (!(url instanceof OkHttpUrl)) {
        throw new IllegalArgumentException("HttpUrl must be OkHttpUrl implementation");
      }
      okhttp3.HttpUrl okUrl = ((OkHttpUrl) url).unwrap();
      this.delegate.url(okUrl);
      return this;
    }

    @Override
    public HttpRequest.Builder url(String url) {
      this.delegate.url(url);
      return this;
    }

    @Override
    public HttpRequest.Builder get() {
      this.delegate.get();
      return this;
    }

    @Override
    public HttpRequest.Builder post(HttpRequestBody body) {
      if (!(body instanceof OkHttpRequestBody)) {
        throw new IllegalArgumentException(
            "HttpRequestBody must be OkHttpRequestBody implementation");
      }
      okhttp3.RequestBody requestBody = ((OkHttpRequestBody) body).unwrap();
      this.delegate.post(requestBody);
      return this;
    }

    @Override
    public HttpRequest.Builder put(HttpRequestBody body) {
      if (!(body instanceof OkHttpRequestBody)) {
        throw new IllegalArgumentException(
            "HttpRequestBody must be OkHttpRequestBody implementation");
      }
      okhttp3.RequestBody requestBody = ((OkHttpRequestBody) body).unwrap();
      this.delegate.put(requestBody);
      return this;
    }

    @Override
    public HttpRequest.Builder header(String name, String value) {
      this.delegate.header(name, value);
      return this;
    }

    @Override
    public HttpRequest.Builder addHeader(String name, String value) {
      this.delegate.addHeader(name, value);
      return this;
    }

    @Override
    public HttpRequest.Builder listener(@Nullable HttpRequestListener listener) {
      this.delegate.tag(OkHttpListener.class, OkHttpListener.wrap(listener));
      return this;
    }

    @Override
    public HttpRequest build() {
      return new OkHttpRequest(this.delegate.build());
    }
  }
}
