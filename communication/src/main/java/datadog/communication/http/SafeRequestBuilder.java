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
  public static class Builder {
    private final Request.Builder requestBuilder;

    public Builder() {
      this.requestBuilder = new Request.Builder();
    }

    // Constructs a SafeRequestBuilder from an existing request
    public Builder(Request.Builder request) {
      this.requestBuilder = request;
    }

    public SafeRequestBuilder.Builder url(HttpUrl url) {
      this.requestBuilder.url(url);
      return this;
    }

    public SafeRequestBuilder.Builder url(String url) {
      this.requestBuilder.url(url);
      return this;
    }

    public SafeRequestBuilder.Builder url(URL url) {
      this.requestBuilder.url(url);
      return this;
    }

    public SafeRequestBuilder.Builder header(String name, String value) {
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

    public SafeRequestBuilder.Builder addHeader(String name, String value) {
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

    public SafeRequestBuilder.Builder removeHeader(String name) {
      this.requestBuilder.removeHeader(name);
      return this;
    }

    public SafeRequestBuilder.Builder headers(Headers headers) {
      this.requestBuilder.headers(headers);
      return this;
    }

    public SafeRequestBuilder.Builder cacheControl(CacheControl cacheControl) {
      this.requestBuilder.cacheControl(cacheControl);
      return this;
    }

    public SafeRequestBuilder.Builder get() {
      this.requestBuilder.get();
      return this;
    }

    public SafeRequestBuilder.Builder head() {
      this.requestBuilder.head();
      return this;
    }

    public SafeRequestBuilder.Builder post(RequestBody body) {
      this.requestBuilder.post(body);
      return this;
    }

    public SafeRequestBuilder.Builder delete(@Nullable RequestBody body) {
      this.requestBuilder.delete(body);
      return this;
    }

    public SafeRequestBuilder.Builder delete() {
      this.requestBuilder.delete();
      return this;
    }

    public SafeRequestBuilder.Builder put(RequestBody body) {
      this.requestBuilder.put(body);
      return this;
    }

    public SafeRequestBuilder.Builder patch(RequestBody body) {
      this.requestBuilder.patch(body);
      return this;
    }

    public SafeRequestBuilder.Builder method(String method, @Nullable RequestBody body) {
      this.requestBuilder.method(method, body);
      return this;
    }

    public SafeRequestBuilder.Builder tag(@Nullable Object tag) {
      this.requestBuilder.tag(tag);
      return this;
    }

    public <T> SafeRequestBuilder.Builder tag(Class<? super T> type, @Nullable T tag) {
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
}
