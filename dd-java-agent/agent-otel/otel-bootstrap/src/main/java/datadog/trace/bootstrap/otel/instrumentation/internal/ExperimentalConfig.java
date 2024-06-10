package datadog.trace.bootstrap.otel.instrumentation.internal;

import static java.util.Collections.emptyList;

import java.util.List;

public final class ExperimentalConfig {
  private static final ExperimentalConfig INSTANCE =
      new ExperimentalConfig(InstrumentationConfig.get());

  private final InstrumentationConfig config;

  private final List<String> messagingHeaders;

  public static ExperimentalConfig get() {
    return INSTANCE;
  }

  public ExperimentalConfig(InstrumentationConfig config) {
    this.config = config;
    messagingHeaders =
        config.getList("otel.instrumentation.messaging.experimental.capture-headers", emptyList());
  }

  public boolean controllerTelemetryEnabled() {
    return config.getBoolean(
        "otel.instrumentation.common.experimental.controller-telemetry.enabled", false);
  }

  public boolean viewTelemetryEnabled() {
    return config.getBoolean(
        "otel.instrumentation.common.experimental.view-telemetry.enabled", false);
  }

  public boolean messagingReceiveInstrumentationEnabled() {
    return config.getBoolean(
        "otel.instrumentation.messaging.experimental.receive-telemetry.enabled", false);
  }

  public boolean indyEnabled() {
    return false;
  }

  public List<String> getMessagingHeaders() {
    return messagingHeaders;
  }
}
