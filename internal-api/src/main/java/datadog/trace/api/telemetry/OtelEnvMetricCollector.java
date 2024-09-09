package datadog.trace.api.telemetry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelEnvMetricCollector
    implements MetricCollector<OtelEnvMetricCollector.OtelEnvMetric> {
  private static final Logger log = LoggerFactory.getLogger(OtelEnvMetricCollector.class);
  private static final String OTEL_ENV_HIDING_METRIC_NAME = "otel.env.hiding";
  private static final String OTEL_ENV_INVALID_METRIC_NAME = "otel.env.invalid";
  private static final String OTEL_ENV_UNSUPPORTED_METRIC_NAME = "otel.env.unsupported";
  private static final String CONFIG_OTEL_KEY_TAG = "config_opentelemetry:";
  private static final String CONFIG_DATADOG_KEY_TAG = "config_datadog:";
  private static final String NAMESPACE = "tracers";
  private static final OtelEnvMetricCollector INSTANCE = new OtelEnvMetricCollector();

  private final BlockingQueue<OtelEnvMetricCollector.OtelEnvMetric> metricsQueue;

  private OtelEnvMetricCollector() {
    this.metricsQueue = new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);
  }

  public static OtelEnvMetricCollector getInstance() {
    return INSTANCE;
  }

  public void setHidingOtelEnvVarMetric(String otelName, String ddName) {
    setMetricOtelEnvVarMetric(
        OTEL_ENV_HIDING_METRIC_NAME,
        CONFIG_OTEL_KEY_TAG + otelName,
        CONFIG_DATADOG_KEY_TAG + ddName);
  }

  public void setInvalidOtelEnvVarMetric(String otelName, String ddName) {
    setMetricOtelEnvVarMetric(
        OTEL_ENV_INVALID_METRIC_NAME,
        CONFIG_OTEL_KEY_TAG + otelName,
        CONFIG_DATADOG_KEY_TAG + ddName);
  }

  public void setUnsupportedOtelEnvVarMetric(String otelName) {
    setMetricOtelEnvVarMetric(OTEL_ENV_UNSUPPORTED_METRIC_NAME, CONFIG_OTEL_KEY_TAG + otelName);
  }

  private void setMetricOtelEnvVarMetric(String metricName, final String... tags) {
    if (!metricsQueue.offer(
        new OtelEnvMetricCollector.OtelEnvMetric(NAMESPACE, true, metricName, "count", 1, tags))) {
      log.debug("Unable to add telemetry metric {} for {}", metricName, tags[0]);
    }
  }

  @Override
  public void prepareMetrics() {
    // Nothing to do here
  }

  @Override
  public Collection<OtelEnvMetricCollector.OtelEnvMetric> drain() {
    if (this.metricsQueue.isEmpty()) {
      return Collections.emptyList();
    }
    List<OtelEnvMetricCollector.OtelEnvMetric> drained = new ArrayList<>(this.metricsQueue.size());
    this.metricsQueue.drainTo(drained);
    return drained;
  }

  public static class OtelEnvMetric extends MetricCollector.Metric {
    public OtelEnvMetric(
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
