package datadog.telemetry;

import datadog.telemetry.api.Payload;
import datadog.telemetry.api.RequestType;

public class TelemetryData {
  private final RequestType requestType;
  private final Payload payload;

  TelemetryData(RequestType requestType, Payload payload) {
    this.requestType = requestType;
    this.payload = payload;
  }

  public RequestType getRequestType() {
    return requestType;
  }

  public Payload getPayload() {
    return payload;
  }
}
