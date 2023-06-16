package datadog.trace.api.metrics;

/**
 * This class denotes a metric name.
 *
 * @See <a href="https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/producing-telemetry.md#generate-metrics">the telemetry documentation</a>
 */
public class MetricName {
  public final String namespace;
  public final boolean common;
  public final String name;

  private MetricName(String namespace, boolean common, String name) {
    this.namespace = namespace;
    this.common = common;
    this.name = name;
  }

  /**
   * Create a metric name.
   *
   * @param namespace The metric name space.
   * @param name      The metric name.
   * @param common    Whether the metric is common ({@code true}) or language specific ({@code false}).
   * @return A metric name.
   */
  public static MetricName named(String namespace, boolean common, String name) {
    return new MetricName(namespace, common, name);
  }
}
