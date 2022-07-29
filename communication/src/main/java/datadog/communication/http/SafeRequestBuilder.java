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
    public Builder(Request request) {
      this.requestBuilder = request.newBuilder();
    }

    public Request.Builder url(HttpUrl url) {
      return this.requestBuilder.url(url);
    }

    public Request.Builder url(String url) {
      return this.requestBuilder.url(url);
    }

    public Request.Builder url(URL url) {
      return this.requestBuilder.url(url);
    }

    public Request.Builder header(String name, String value) {
      // This try/catch block prevents header secrets from being outputted to
      // console or logs.
      try {
        return this.requestBuilder.header(name, value);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            new StringBuilder()
                .append("InvalidArgumentException at header() for header: ")
                .append(name)
                .toString());
      }
    }

    public Request.Builder addHeader(String name, String value) {
      // This try/catch block prevents header secrets from being outputted to
      // console or logs.
      try {
        return this.requestBuilder.addHeader(name, value);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            new StringBuilder()
                .append("InvalidArgumentException at addHeader() for header: ")
                .append(name)
                .toString());
      }
    }

    public Request.Builder removeHeader(String name) {
      return this.requestBuilder.removeHeader(name);
    }

    public Request.Builder headers(Headers headers) {
      return this.requestBuilder.headers(headers);
    }

    public Request.Builder cacheControl(CacheControl cacheControl) {
      return this.requestBuilder.cacheControl(cacheControl);
    }

    public Request.Builder get() {
      return this.requestBuilder.get();
    }

    public Request.Builder head() {
      return this.requestBuilder.head();
    }

    public Request.Builder post(RequestBody body) {
      return this.requestBuilder.post(body);
    }

    public Request.Builder delete(@Nullable RequestBody body) {
      return this.requestBuilder.delete(body);
    }

    public Request.Builder delete() {
      return this.requestBuilder.delete();
    }

    public Request.Builder put(RequestBody body) {
      return this.requestBuilder.put(body);
    }

    public Request.Builder patch(RequestBody body) {
      return this.requestBuilder.patch(body);
    }

    public Request.Builder method(String method, @Nullable RequestBody body) {
      return this.requestBuilder.method(method, body);
    }

    public Request.Builder tag(@Nullable Object tag) {
      return this.requestBuilder.tag(tag);
    }

    public <T> Request.Builder tag(Class<? super T> type, @Nullable T tag) {
      return this.requestBuilder.tag(type, tag);
    }

    public Request build() {
      return this.requestBuilder.build();
    }

    public Request.Builder getBuilder() {
      return this.requestBuilder;
    }
  }
}
