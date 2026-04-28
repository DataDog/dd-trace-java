package datadog.communication.http.client;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable request model used by client facades. */
public final class HttpClientRequest {
  private final URI uri;
  private final String method;
  private final Map<String, List<String>> headers;
  private final byte[] body;

  private HttpClientRequest(
      URI uri, String method, Map<String, List<String>> headers, byte[] body) {
    this.uri = uri;
    this.method = method;
    this.headers = headers;
    this.body = body;
  }

  public URI uri() {
    return uri;
  }

  public String method() {
    return method;
  }

  public Map<String, List<String>> headers() {
    return headers;
  }

  public byte[] body() {
    return body;
  }

  public static Builder builder(URI uri, String method) {
    return new Builder(uri, method);
  }

  public static final class Builder {
    private final URI uri;
    private final String method;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];

    private Builder(URI uri, String method) {
      this.uri = uri;
      this.method = method;
    }

    public Builder addHeader(String name, String value) {
      headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
      return this;
    }

    public Builder headers(Map<String, String> values) {
      for (Map.Entry<String, String> entry : values.entrySet()) {
        addHeader(entry.getKey(), entry.getValue());
      }
      return this;
    }

    public Builder body(byte[] bytes) {
      this.body = bytes != null ? bytes : new byte[0];
      return this;
    }

    public Builder body(List<ByteBuffer> buffers) {
      int totalLength = 0;
      for (ByteBuffer buffer : buffers) {
        totalLength += buffer.remaining();
      }
      byte[] merged = new byte[totalLength];
      int offset = 0;
      for (ByteBuffer buffer : buffers) {
        int remaining = buffer.remaining();
        buffer.duplicate().get(merged, offset, remaining);
        offset += remaining;
      }
      this.body = merged;
      return this;
    }

    public HttpClientRequest build() {
      Map<String, List<String>> frozenHeaders = new LinkedHashMap<>();
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        frozenHeaders.put(
            entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
      }
      return new HttpClientRequest(
          uri, method, Collections.unmodifiableMap(frozenHeaders), body.clone());
    }
  }
}
