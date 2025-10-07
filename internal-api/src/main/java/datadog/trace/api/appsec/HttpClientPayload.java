package datadog.trace.api.appsec;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public abstract class HttpClientPayload {

  private final long requestId;
  private final Map<String, List<String>> headers;
  private MediaType contentType;
  private InputStream body;

  protected HttpClientPayload(final long requestId, final Map<String, List<String>> headers) {
    this.requestId = requestId;
    this.headers = headers;
  }

  public long getRequestId() {
    return requestId;
  }

  public MediaType getContentType() {
    return contentType;
  }

  public Map<String, List<String>> getHeaders() {
    return headers;
  }

  public InputStream getBody() {
    return body;
  }

  public void setBody(MediaType contentType, InputStream body) {
    this.contentType = contentType;
    this.body = body;
  }
}
