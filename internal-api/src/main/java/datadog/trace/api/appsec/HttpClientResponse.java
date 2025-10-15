package datadog.trace.api.appsec;

import java.util.List;
import java.util.Map;

public class HttpClientResponse extends HttpClientPayload {

  private final int status;

  public HttpClientResponse(
      final long requestId, final int status, final Map<String, List<String>> headers) {
    super(requestId, headers);
    this.status = status;
  }

  public int getStatus() {
    return status;
  }
}
