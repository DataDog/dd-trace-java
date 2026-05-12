package datadog.trace.api.telemetry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Collects telemetry about OpenTelemetry SPIs detected in the customer environment. */
public class OtelSpiCollector implements MetricCollector<OtelSpiCollector.OtelSpiMetric> {
  private static final Logger log = LoggerFactory.getLogger(OtelSpiCollector.class);
  private static final String OTEL_SPI_DETECTED_METRIC_NAME = "otel.spi.detected";
  private static final String SPI_CLASS_TAG = "spi_class:";
  private static final String SOURCE_TAG = "source:";
  private static final String NAMESPACE = "tracers";
  private static final OtelSpiCollector INSTANCE = new OtelSpiCollector();

  private final BlockingQueue<OtelSpiMetric> metricsQueue;

  private OtelSpiCollector() {
    this.metricsQueue = new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);
  }

  public static OtelSpiCollector getInstance() {
    return INSTANCE;
  }

  public void recordSpiDetected(String spiFqn, String source) {
    if (!metricsQueue.offer(
        new OtelSpiMetric(
            NAMESPACE,
            true,
            OTEL_SPI_DETECTED_METRIC_NAME,
            "count",
            1,
            SPI_CLASS_TAG + spiFqn,
            SOURCE_TAG + source))) {
      log.debug(
          "Unable to add telemetry metric {} for spi_class={} source={}",
          OTEL_SPI_DETECTED_METRIC_NAME,
          spiFqn,
          source);
    }
  }

  @Override
  public void prepareMetrics() {
    // Nothing to do here
  }

  @Override
  public Collection<OtelSpiMetric> drain() {
    if (this.metricsQueue.isEmpty()) {
      return Collections.emptyList();
    }
    List<OtelSpiMetric> drained = new ArrayList<>(this.metricsQueue.size());
    this.metricsQueue.drainTo(drained);
    return drained;
  }

  public static class OtelSpiMetric extends MetricCollector.Metric {
    public OtelSpiMetric(
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
