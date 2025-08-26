package datadog.config.telemetry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigInversionMetricCollector
    implements MetricCollector<ConfigInversionMetricCollector.ConfigInversionMetric> {
  private static final Logger log = LoggerFactory.getLogger(ConfigInversionMetricCollector.class);
  private static final String CONFIG_INVERSION_KEY_TAG = "config_name:";
  private static final String CONFIG_INVERSION_METRIC_NAME = "untracked.config.detected";
  private static final String NAMESPACE = "tracers";
  private static final ConfigInversionMetricCollector INSTANCE =
      new ConfigInversionMetricCollector();

  private final BlockingQueue<ConfigInversionMetricCollector.ConfigInversionMetric> metricsQueue;

  private ConfigInversionMetricCollector() {
    this.metricsQueue = new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);
  }

  public static ConfigInversionMetricCollector getInstance() {
    return INSTANCE;
  }

  public void setUndocumentedEnvVarMetric(String configName) {
    setMetricConfigInversionMetric(CONFIG_INVERSION_KEY_TAG + configName);
  }

  private void setMetricConfigInversionMetric(final String... tags) {
    if (!metricsQueue.offer(
        new ConfigInversionMetricCollector.ConfigInversionMetric(
            NAMESPACE, true, CONFIG_INVERSION_METRIC_NAME, "count", 1, tags))) {
      log.debug("Unable to add telemetry metric {} for {}", CONFIG_INVERSION_METRIC_NAME, tags[0]);
    }
  }

  @Override
  public void prepareMetrics() {
    // Nothing to do here
  }

  @Override
  public Collection<ConfigInversionMetricCollector.ConfigInversionMetric> drain() {
    if (this.metricsQueue.isEmpty()) {
      return Collections.emptyList();
    }
    List<ConfigInversionMetricCollector.ConfigInversionMetric> drained =
        new ArrayList<>(this.metricsQueue.size());
    this.metricsQueue.drainTo(drained);
    return drained;
  }

  public static class ConfigInversionMetric extends MetricCollector.Metric {
    public ConfigInversionMetric(
        String namespace,
        boolean common,
        String metricName,
        String type,
        Number value,
        final String... tags) {
      super(namespace, common, metricName, type, value, tags);
    }
  }
}
