package datadog.trace.api.metrics;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** This class describes an abstract instrument capable of recording timed measures. */
public abstract class Instrument {
  protected final String name;
  protected final boolean common;
  protected final List<String> tags;
  protected AtomicBoolean updated;

  /**
   * Constructor.
   *
   * @param name The metric name.
   * @param common Whether the metric is common ({@code true}) or language specific ({@code false}).
   * @param tags The metric tags.
   */
  protected Instrument(String name, boolean common, List<String> tags) {
    this.name = name;
    this.common = common;
    this.tags = tags;
    this.updated = new AtomicBoolean(false);
  }

  /**
   * Gets the metric name.
   *
   * @return The metric name.
   */
  public String getName() {
    return this.name;
  }

  /**
   * Gets the metric type.
   *
   * @return The metric type, according <a
   *     href="https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/producing-telemetry.md#generate-metrics">the
   *     documentation</a>.
   */
  public abstract String getType();

  /**
   * Gets whether the metric is common or language specific.
   *
   * @return Whether the metric is common ({@code true}) or language specific ({@code false}).
   */
  public boolean isCommon() {
    return this.common;
  }

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
