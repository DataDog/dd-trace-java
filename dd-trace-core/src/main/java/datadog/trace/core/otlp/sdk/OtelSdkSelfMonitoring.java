package datadog.trace.core.otlp.sdk;

import datadog.trace.api.Config;
import datadog.trace.core.monitor.HealthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gate and lazy holder for the OTel SDK self-monitoring metrics ({@code otel.sdk.*}), which record
 * directly on the bootstrap metric registry. This class references no registry or OTel API type:
 * the classes that do load only once {@link Config#isOtelSdkSelfMonitoringMetricsActive()} holds,
 * so dd-trace-core without otel-bootstrap on its classpath never resolves them.
 */
public final class OtelSdkSelfMonitoring {
  private static final Logger log = LoggerFactory.getLogger(OtelSdkSelfMonitoring.class);

  private OtelSdkSelfMonitoring() {}

  /**
   * Returns the OTel {@link HealthMetrics} when the gate is on and its classes load, otherwise
   * {@code null}, so callers can skip it when collecting the enabled health metrics delegates.
   */
  public static HealthMetrics healthMetrics(Config config) {
    if (!config.isOtelSdkSelfMonitoringMetricsActive()) {
      return null;
    }
    try {
      return OtelHealthMetrics.INSTANCE;
    } catch (Throwable e) {
      log.debug("OTel SDK self-monitoring span metrics are unavailable", e);
      return null;
    }
  }
}
