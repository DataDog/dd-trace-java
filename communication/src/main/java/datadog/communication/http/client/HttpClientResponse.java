package datadog.communication.http.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/** Immutable in-memory HTTP response model used by client facades. */
public final class HttpClientResponse implements AutoCloseable {
  private final int statusCode;
  private final Map<String, List<String>> headers;
  private final byte[] body;

  public HttpClientResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
    this.statusCode = statusCode;

    Map<String, List<String>> frozenHeaders = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      frozenHeaders.put(
          entry.getKey().toLowerCase(Locale.ROOT),
          Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
    }

    this.headers = Collections.unmodifiableMap(frozenHeaders);
    this.body = body.clone();
  }

  public int statusCode() {
    return statusCode;
  }

  public Map<String, List<String>> headers() {
    return headers;
  }

  @Nullable
  public String header(String name) {
    List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  public byte[] body() {
    return body.clone();
  }

  public InputStream bodyStream() {
    return new ByteArrayInputStream(body);
  }

  @Override
  public void close() {
    // no-op for an in-memory response
  }
}
