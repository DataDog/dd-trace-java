package io.opentelemetry.sdk.autoconfigure;

import datadog.opentelemetry.DDOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;

public class AutoConfiguredOpenTelemetrySdk {
  private final DDOpenTelemetry openTelemetry = new DDOpenTelemetry();

  public static AutoConfiguredOpenTelemetrySdk initialize() {
    return new AutoConfiguredOpenTelemetrySdk();
  }

  public OpenTelemetry getOpenTelemetrySdk() {
    return this.openTelemetry;
  }
}
