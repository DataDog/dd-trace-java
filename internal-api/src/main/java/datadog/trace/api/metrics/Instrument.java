package datadog.trace.api.metrics;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** This class describes an abstract instrument capable of recording timed measures. */
public abstract class Instrument {
  protected final MetricName name;
  protected final List<String> tags;
  protected AtomicBoolean updated;

  /**
   * Constructor.
   *
   * @param name The metric name.
   * @param tags The metric tags.
   */
  protected Instrument(MetricName name, List<String> tags) {
    this.name = name;
    this.tags = tags;
    this.updated = new AtomicBoolean(false);
  }

  /**
   * Gets the metric name.
   *
   * @return The metric name.
   */
  public MetricName getName() {
    return this.name;
  }

  /**
   * Gets the metric type.
   *
   * @return The metric type, according <a
   *     href="https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/producing-telemetry.md#generate-metrics">the
   *     documentation</a>.
   */
  public abstract MetricType getType();

  /**
   * Get the metric tags.
   *
   * @return The metric tags.
   */
  public List<String> getTags() {
    return this.tags;
  }

  /**
   * Gets the metric value.
   *
   * @return The metric value.
   */
  public abstract Number getValue();

  /** Clear instrument value and updated flag. */
  public void reset() {
    this.updated.set(false);
    resetValue();
  }

  protected abstract void resetValue();
}
