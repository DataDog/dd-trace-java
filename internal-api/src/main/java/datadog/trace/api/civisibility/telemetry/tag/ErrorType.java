package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** The type of HTTP request error */
public enum ErrorType implements TagValue {
  TIMEOUT,
  NETWORK,
  STATUS_CODE_4XX_RESPONSE,
  STATUS_CODE_5XX_RESPONSE;

  private final String s;

  ErrorType() {
    s = "error_type:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }

  public static ErrorType from(int responseCode) {
    int category = (responseCode / 100) % 10;
    switch (category) {
      case 4:
        return STATUS_CODE_4XX_RESPONSE;
      case 5:
        return STATUS_CODE_5XX_RESPONSE;
      default:
        return NETWORK;
    }
  }
}
