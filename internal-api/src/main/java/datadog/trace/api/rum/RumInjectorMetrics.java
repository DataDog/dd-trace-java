package datadog.trace.api.rum;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
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

  private final Queue<MetricCollector.Metric> metrics = new LinkedBlockingQueue<>(1024);
  private final Queue<MetricCollector.DistributionSeriesPoint> distributions =
      new LinkedBlockingQueue<>(1024);

  private final DDCache<String, List<String>> succeedTagsCache = DDCaches.newFixedSizeCache(8);
  private final DDCache<String, List<String>> skippedTagsCache = DDCaches.newFixedSizeCache(8);
  private final DDCache<String, List<String>> cspTagsCache = DDCaches.newFixedSizeCache(8);
  private final DDCache<String, List<String>> responseTagsCache = DDCaches.newFixedSizeCache(8);
  private final DDCache<String, List<String>> timeTagsCache = DDCaches.newFixedSizeCache(8);
  private final DDCache<String, List<String>> failedTagsCache = DDCaches.newFixedSizeCache(16);
  private final DDCache<String, List<String>> initTagsCache = DDCaches.newFixedSizeCache(1);

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
        succeedTagsCache.computeIfAbsent(
            servletVersion,
            version ->
                Arrays.asList(
                    "application_id:" + applicationId,
                    "integration_name:servlet",
                    "integration_version:" + version,
                    "remote_config_used:" + remoteConfigUsed));

    MetricCollector.Metric metric =
        new MetricCollector.Metric("rum", true, "injection.succeed", "count", 1, tags);
    metrics.offer(metric);
  }

  @Override
  public void onInjectionFailed(String servletVersion, String contentEncoding) {
    String cacheKey = servletVersion + ":" + contentEncoding;
    List<String> tags =
        failedTagsCache.computeIfAbsent(
            cacheKey,
            key -> {
              if (contentEncoding != null) {
                return Arrays.asList(
                    "application_id:" + applicationId,
                    "content_encoding:" + contentEncoding,
                    "integration_name:servlet",
                    "integration_version:" + servletVersion,
                    "reason:failed_to_return_response_wrapper",
                    "remote_config_used:" + remoteConfigUsed);
              } else {
                return Arrays.asList(
                    "application_id:" + applicationId,
                    "integration_name:servlet",
                    "integration_version:" + servletVersion,
                    "reason:failed_to_return_response_wrapper",
                    "remote_config_used:" + remoteConfigUsed);
              }
            });

    MetricCollector.Metric metric =
        new MetricCollector.Metric("rum", true, "injection.failed", "count", 1, tags);
    metrics.offer(metric);
  }

  @Override
  public void onInjectionSkipped(String servletVersion) {
    List<String> tags =
        skippedTagsCache.computeIfAbsent(
            servletVersion,
            version ->
                Arrays.asList(
                    "application_id:" + applicationId,
                    "integration_name:servlet",
                    "integration_version:" + version,
                    "reason:should_not_inject",
                    "remote_config_used:" + remoteConfigUsed));

    MetricCollector.Metric metric =
        new MetricCollector.Metric("rum", true, "injection.skipped", "count", 1, tags);
    metrics.offer(metric);
  }

  @Override
  public void onInitializationSucceed() {
    List<String> tags =
        initTagsCache.computeIfAbsent(
            "init", key -> Arrays.asList("integration_name:servlet", "integration_version:N/A"));

    MetricCollector.Metric metric =
        new MetricCollector.Metric(
            "rum", true, "injection.initialization.succeed", "count", 1, tags);
    metrics.offer(metric);
  }

  @Override
  public void onContentSecurityPolicyDetected(String servletVersion) {
    List<String> tags =
        cspTagsCache.computeIfAbsent(
            servletVersion,
            version ->
                Arrays.asList(
                    "integration_name:servlet",
                    "integration_version:" + version,
                    "kind:header",
                    "reason:csp_header_found",
                    "status:seen"));

    MetricCollector.Metric metric =
        new MetricCollector.Metric(
            "rum", true, "injection.content_security_policy", "count", 1, tags);
    metrics.offer(metric);
  }

  @Override
  public void onInjectionResponseSize(String servletVersion, long bytes) {
    List<String> tags =
        responseTagsCache.computeIfAbsent(
            servletVersion,
            version ->
                Arrays.asList(
                    "integration_name:servlet",
                    "integration_version:" + version,
                    "response_kind:header"));

    MetricCollector.DistributionSeriesPoint distribution =
        new MetricCollector.DistributionSeriesPoint(
            "injection.response.bytes", true, "rum", (int) bytes, tags);
    distributions.offer(distribution);
  }

  @Override
  public void onInjectionTime(String servletVersion, long milliseconds) {
    List<String> tags =
        timeTagsCache.computeIfAbsent(
            servletVersion,
            version -> Arrays.asList("integration_name:servlet", "integration_version:" + version));

    MetricCollector.DistributionSeriesPoint distribution =
        new MetricCollector.DistributionSeriesPoint(
            "injection.ms", true, "rum", (int) milliseconds, tags);
    distributions.offer(distribution);
  }

  @Override
  public void close() {
    metrics.clear();
    distributions.clear();
    succeedTagsCache.clear();
    skippedTagsCache.clear();
    cspTagsCache.clear();
    responseTagsCache.clear();
    timeTagsCache.clear();
    failedTagsCache.clear();
    initTagsCache.clear();
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

    List<MetricCollector.Metric> drained = new ArrayList<>(metrics);
    metrics.clear();
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

    List<MetricCollector.DistributionSeriesPoint> drained = new ArrayList<>(distributions);
    distributions.clear();
    return drained;
  }
}
