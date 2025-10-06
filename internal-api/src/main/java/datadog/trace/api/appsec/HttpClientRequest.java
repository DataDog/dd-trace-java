package datadog.trace.api.appsec;

import java.util.List;
import java.util.Map;

public class HttpClientRequest extends HttpClientPayload {

  private final String url;
  private final String method;

  public HttpClientRequest(final long id, final String url) {
    this(id, url, null, null);
  }

  public HttpClientRequest(
      final long id,
      final String url,
      final String method,
      final Map<String, List<String>> headers) {
    super(id, headers);
    this.url = url;
    this.method = method;
  }

  public String getUrl() {
    return url;
  }

  public String getMethod() {
    return method;
  }
}
