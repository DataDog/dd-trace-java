package datadog.communication.http;

import java.net.URL;
import javax.annotation.Nullable;
import okhttp3.CacheControl;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;

/*
The purpose of this class is to wrap okhttp3.Request.Builder.
This adapter is used to resolve a vulnerability in Request.builder where
the .header() and .addHeader() methods can cause header secrets to be
printed/logged when invalid characters are parsed.
 */
public final class SafeRequestBuilder {

  private final Request.Builder requestBuilder;

  public SafeRequestBuilder() {
    this.requestBuilder = new Request.Builder();
  }

  // Constructs a SafeRequestBuilder from an existing request
  public SafeRequestBuilder(Request.Builder request) {
    this.requestBuilder = request;
  }

  public SafeRequestBuilder url(HttpUrl url) {
    this.requestBuilder.url(url);
    return this;
  }

  public SafeRequestBuilder url(String url) {
    this.requestBuilder.url(url);
    return this;
  }

  public SafeRequestBuilder url(URL url) {
    this.requestBuilder.url(url);
    return this;
  }

  public SafeRequestBuilder header(String name, String value) {
    // This try/catch block prevents header secrets from being outputted to
    // console or logs.
    try {
      this.requestBuilder.header(name, value);
      return this;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          new StringBuilder()
              .append("InvalidArgumentException at header() for header: ")
              .append(name)
              .toString());
    }
  }

  public SafeRequestBuilder addHeader(String name, String value) {
    // This try/catch block prevents header secrets from being outputted to
    // console or logs.
    try {
      this.requestBuilder.addHeader(name, value);
      return this;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          new StringBuilder()
              .append("InvalidArgumentException at addHeader() for header: ")
              .append(name)
              .toString());
    }
  }

  public static Request.Builder addHeader(Request.Builder builder, String name, String value) {
    try {
      return builder.addHeader(name, value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          new StringBuilder()
              .append("InvalidArgumentException at addHeader() for header: ")
              .append(name)
              .toString());
    }
  }

  public SafeRequestBuilder removeHeader(String name) {
    this.requestBuilder.removeHeader(name);
    return this;
  }

  public SafeRequestBuilder headers(Headers headers) {
    this.requestBuilder.headers(headers);
    return this;
  }

  public SafeRequestBuilder cacheControl(CacheControl cacheControl) {
    this.requestBuilder.cacheControl(cacheControl);
    return this;
  }

  public SafeRequestBuilder get() {
    this.requestBuilder.get();
    return this;
  }

  public SafeRequestBuilder head() {
    this.requestBuilder.head();
    return this;
  }

  public SafeRequestBuilder post(RequestBody body) {
    this.requestBuilder.post(body);
    return this;
  }

  public SafeRequestBuilder delete(@Nullable RequestBody body) {
    this.requestBuilder.delete(body);
    return this;
  }

  public SafeRequestBuilder delete() {
    this.requestBuilder.delete();
    return this;
  }

  public SafeRequestBuilder put(RequestBody body) {
    this.requestBuilder.put(body);
    return this;
  }

  public SafeRequestBuilder patch(RequestBody body) {
    this.requestBuilder.patch(body);
    return this;
  }

  public SafeRequestBuilder method(String method, @Nullable RequestBody body) {
    this.requestBuilder.method(method, body);
    return this;
  }

  public SafeRequestBuilder tag(@Nullable Object tag) {
    this.requestBuilder.tag(tag);
    return this;
  }

  public <T> SafeRequestBuilder tag(Class<? super T> type, @Nullable T tag) {
    this.requestBuilder.tag(type, tag);
    return this;
  }

  public Request build() {
    return this.requestBuilder.build();
  }

  public Request.Builder getBuilder() {
    return this.requestBuilder;
  }
}
