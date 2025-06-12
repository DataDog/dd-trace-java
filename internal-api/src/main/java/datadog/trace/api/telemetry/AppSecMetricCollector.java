package datadog.trace.api.telemetry;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ProductActivation;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class AppSecMetricCollector implements MetricCollector<MetricCollector.Metric> {
  private static ConfigOrigin beforeRemote;

  private static ConfigOrigin latestAppsecOrigin;

  public static AppSecMetricCollector INSTANCE = new AppSecMetricCollector();

  public static AppSecMetricCollector get() {
    return AppSecMetricCollector.INSTANCE;
  }

  private AppSecMetricCollector() {
    // Prevent external instantiation
  }

  private static final String NAMESPACE = "appsec";

  private static final BlockingQueue<MetricCollector.Metric> rawMetricsQueue =
      new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);

  public static void setLatestAppsecOrigin(ConfigOrigin origin) {
    if (latestAppsecOrigin != null && origin == ConfigOrigin.REMOTE) {
      // If the origin is already set to one of the other origins,
      // remote is not allowed to override it as all others take precedence
      return;
    }

    beforeRemote = latestAppsecOrigin;
    if (origin == null) {
      latestAppsecOrigin = ConfigOrigin.UNKNOWN;
    } else {
      latestAppsecOrigin = origin;
    }
  }

  public static void returnToOldOrigin() {
    latestAppsecOrigin = beforeRemote;
    beforeRemote = null;
  }

  public void appSecEnabled() {
    rawMetricsQueue.offer(new AppSecEnabledRawMetric(latestAppsecOrigin));
  }

  @Override
  public Collection<MetricCollector.Metric> drain() {
    if (!rawMetricsQueue.isEmpty()) {
      List<MetricCollector.Metric> list = new LinkedList<>();
      int drained = rawMetricsQueue.drainTo(list);
      if (drained > 0) {
        return list;
      }
    }
    return Collections.emptyList();
  }

  @Override
  public void prepareMetrics() {
    // Periodically report AppSec enabled status
    final ProductActivation appSecActivation = Config.get().getAppSecActivation();
    if (appSecActivation == ProductActivation.FULLY_ENABLED
        || ConfigOrigin.REMOTE == latestAppsecOrigin) { // remote enablement can happen later
      appSecEnabled();
    }
  }

  public static class AppSecEnabledRawMetric extends MetricCollector.Metric {
    public static final String APPSEC_ENABLED = "enabled";
    public static final String APPSEC_ENABLED_METRIC_TYPE = "gauge";

    public AppSecEnabledRawMetric(final ConfigOrigin origin) {
      super(
          NAMESPACE,
          true,
          APPSEC_ENABLED,
          APPSEC_ENABLED_METRIC_TYPE,
          1,
          "origin:" + (origin == null ? ConfigOrigin.UNKNOWN.value : origin.value));
    }
  }
}
