package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import javax.annotation.Nullable;

/** The type of HTTP request error */
public enum StatusCode implements TagValue {
  BAD_REQUEST(400),
  UNAUTHORIZED(401),
  FORBIDDEN(403),
  NOT_FOUND(404),
  REQUEST_TIMEOUT(408),
  TOO_MANY_REQUESTS(429);

  private final String s;

  StatusCode(int code) {
    s = "status_code:" + code;
  }

  @Override
  public String asString() {
    return s;
  }

  @Nullable
  public static StatusCode from(int responseCode) {
    switch (responseCode) {
      case 400:
        return BAD_REQUEST;
      case 401:
        return UNAUTHORIZED;
      case 403:
        return FORBIDDEN;
      case 404:
        return NOT_FOUND;
      case 408:
        return REQUEST_TIMEOUT;
      case 429:
        return TOO_MANY_REQUESTS;
      default:
        return null;
    }
  }
}
