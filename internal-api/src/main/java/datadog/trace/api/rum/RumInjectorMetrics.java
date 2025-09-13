package datadog.trace.api.rum;

import datadog.trace.api.Config;
import datadog.trace.api.telemetry.MetricCollector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This class implements the RumTelemetryCollector interface, which is used to collect telemetry
 * from the RumInjector. Metrics are then reported via the Datadog telemetry intake system.
 *
 * @see <a
 *     href="https://github.com/DataDog/dd-go/blob/prod/trace/apps/tracer-telemetry-intake/telemetry-metrics/static/common_metrics.json">common
 *     metrics and tags</a>
 */
public class RumInjectorMetrics implements RumTelemetryCollector {

  private final Queue<MetricCollector.Metric> metrics = new LinkedBlockingQueue<>();
  private final Queue<MetricCollector.DistributionSeriesPoint> distributions =
      new LinkedBlockingQueue<>();

  private final String applicationId;
  private final String remoteConfigUsed;

  public RumInjectorMetrics() {

    // Get RUM config values (applicationId and remoteConfigUsed) for tagging
    RumInjector rumInjector = RumInjector.get();
    RumInjectorConfig injectorConfig = Config.get().getRumInjectorConfig();
    if (rumInjector.isEnabled() && injectorConfig != null) {
      this.applicationId = injectorConfig.applicationId;
      this.remoteConfigUsed = injectorConfig.remoteConfigurationId != null ? "true" : "false";
    } else {
      this.applicationId = "unknown";
      this.remoteConfigUsed = "false";
    }
  }

  @Override
  public void onInjectionSucceed(String servletVersion) {
    List<String> tags =
        Arrays.asList(
            "application_id:" + applicationId,
            "integration_name:servlet",
            "integration_version:" + servletVersion,
            "remote_config_used:" + remoteConfigUsed);

    MetricCollector.Metric metric =
        new MetricCollector.Metric("rum", false, "injection.succeed", "count", 1, tags);
    metrics.offer(metric);
  }

  @Override
  public void onInjectionFailed(String servletVersion, String contentEncoding) {
    List<String> tags = new ArrayList<>();
    tags.add("application_id:" + applicationId);
    if (contentEncoding != null) {
      tags.add("content_encoding:" + contentEncoding);
    }
    tags.add("integration_name:servlet");
    tags.add("integration_version:" + servletVersion);
    tags.add("reason:failed_to_return_response_wrapper");
    tags.add("remote_config_used:" + remoteConfigUsed);

    MetricCollector.Metric metric =
        new MetricCollector.Metric("rum", false, "injection.failed", "count", 1, tags);
    metrics.offer(metric);
  }

  @Override
  public void onInjectionSkipped(String servletVersion) {
    List<String> tags =
        Arrays.asList(
            "application_id:" + applicationId,
            "integration_name:servlet",
            "integration_version:" + servletVersion,
            "reason:should_not_inject",
            "remote_config_used:" + remoteConfigUsed);

    MetricCollector.Metric metric =
        new MetricCollector.Metric("rum", false, "injection.skipped", "count", 1, tags);
    metrics.offer(metric);
  }

  @Override
  public void onInitializationSucceed() {
    List<String> tags = Arrays.asList("integration_name:servlet", "integration_version:N/A");

    MetricCollector.Metric metric =
        new MetricCollector.Metric(
            "rum", false, "injection.initialization.succeed", "count", 1, tags);
    metrics.offer(metric);
  }

  @Override
  public void onContentSecurityPolicyDetected(String servletVersion) {
    List<String> tags =
        Arrays.asList(
            "integration_name:servlet",
            "integration_version:" + servletVersion,
            "kind:header",
            "reason:csp_header_found",
            "status:seen");

    MetricCollector.Metric metric =
        new MetricCollector.Metric(
            "rum", false, "injection.content_security_policy", "count", 1, tags);
    metrics.offer(metric);
  }

  @Override
  public void onInjectionResponseSize(String servletVersion, long bytes) {
    List<String> tags =
        Arrays.asList(
            "integration_name:servlet",
            "integration_version:" + servletVersion,
            "response_kind:header");

    MetricCollector.DistributionSeriesPoint distribution =
        new MetricCollector.DistributionSeriesPoint(
            "injection.response.bytes", false, "rum", (int) bytes, tags);
    distributions.offer(distribution);
  }

  @Override
  public void onInjectionTime(String servletVersion, long milliseconds) {
    List<String> tags =
        Arrays.asList("integration_name:servlet", "integration_version:" + servletVersion);

    MetricCollector.DistributionSeriesPoint distribution =
        new MetricCollector.DistributionSeriesPoint(
            "injection.ms", false, "rum", (int) milliseconds, tags);
    distributions.offer(distribution);
  }

  @Override
  public void close() {
    metrics.clear();
    distributions.clear();
  }

  /**
   * Drains all count metrics.
   *
   * @return Collection of metrics sent via telemetry
   */
  public synchronized Collection<MetricCollector.Metric> drain() {
    if (metrics.isEmpty()) {
      return Collections.emptyList();
    }

    List<MetricCollector.Metric> drained = new ArrayList<>(metrics.size());
    MetricCollector.Metric metric;
    while ((metric = metrics.poll()) != null) {
      drained.add(metric);
    }
    return drained;
  }

  /**
   * Drains all distribution metrics.
   *
   * @return Collection of distribution points sent via telemetry
   */
  public synchronized Collection<MetricCollector.DistributionSeriesPoint>
      drainDistributionSeries() {
    if (distributions.isEmpty()) {
      return Collections.emptyList();
    }

    List<MetricCollector.DistributionSeriesPoint> drained = new ArrayList<>(distributions.size());
    MetricCollector.DistributionSeriesPoint distribution;
    while ((distribution = distributions.poll()) != null) {
      drained.add(distribution);
    }
    return drained;
  }
}
