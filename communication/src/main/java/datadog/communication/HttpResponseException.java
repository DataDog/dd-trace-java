package datadog.communication;

import java.io.IOException;

/** An HTTP request failed with a non-success response. */
public final class HttpResponseException extends IOException {

  private final int statusCode;

  public HttpResponseException(final int statusCode, final String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }
}
