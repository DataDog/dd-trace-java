package datadog.communication.otlp;

import static datadog.communication.ddagent.TracerVersion.TRACER_VERSION;

import datadog.trace.api.Config;
import java.util.function.BiConsumer;

/** Canonical OTLP resource attributes shared by the trace and profile exports. */
public final class OtlpCanonicalResourceAttributes {
  private OtlpCanonicalResourceAttributes() {}

  /**
   * Visits {@code service.name}, {@code deployment.environment.name} and {@code service.version}
   * (when not empty), {@code host.name} (when reported and not empty) and the {@code
   * telemetry.sdk.*} attributes, in that order.
   */
  public static void visit(Config config, BiConsumer<String, String> visitor) {
    visitor.accept("service.name", config.getServiceName());
    String env = config.getEnv();
    if (!env.isEmpty()) {
      visitor.accept("deployment.environment.name", env);
    }
    String version = config.getVersion();
    if (!version.isEmpty()) {
      visitor.accept("service.version", version);
    }
    if (config.isReportHostName()) {
      String hostName = config.getHostName();
      if (hostName != null && !hostName.isEmpty()) {
        visitor.accept("host.name", hostName);
      }
    }
    visitor.accept("telemetry.sdk.name", "datadog");
    visitor.accept("telemetry.sdk.version", TRACER_VERSION);
    visitor.accept("telemetry.sdk.language", "java");
  }
}
