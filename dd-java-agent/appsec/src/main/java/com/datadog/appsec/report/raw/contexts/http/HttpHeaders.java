package com.datadog.appsec.report.raw.contexts.http;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class HttpHeaders {

  private final Map<String, ? extends Collection<String>> headers;

  public HttpHeaders(Map<String, ? extends Collection<String>> headers) {
    this.headers = headers;
  }

  public Map<String, ? extends Collection<String>> getHeaderMap() {
    return headers;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    HttpHeaders that = (HttpHeaders) o;
    return headers.equals(that.headers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(headers);
  }

  public static class HttpHeadersBuilder extends HttpHeadersBuilderBase<HttpHeadersBuilder> {
    @Override
    public HttpHeaders build() {
      return new HttpHeaders(map);
    }
  }

  public abstract static class HttpHeadersBuilderBase<T extends HttpHeadersBuilderBase<T>> {
    protected final Map<String, Collection<String>> map = new HashMap<>();

    protected abstract HttpHeaders build();

    public T withHeader(String name, String value) {
      Collection<String> headers = map.get(name);
      if (headers == null) {
        headers = new ArrayList<>(1);
        map.put(name, headers);
      }
      headers.add(value);
      return (T) this;
    }
  }
}
