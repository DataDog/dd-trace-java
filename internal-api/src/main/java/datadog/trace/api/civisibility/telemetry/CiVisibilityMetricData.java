package datadog.trace.api.civisibility.telemetry;

import datadog.config.telemetry.MetricCollector;

public class CiVisibilityMetricData extends MetricCollector.Metric {

  private static final String NAMESPACE = "civisibility";

  public CiVisibilityMetricData(String metricName, long counter, TagValue... tags) {
    super(NAMESPACE, true, metricName, "count", counter, stringify(tags));
  }

  private static String[] stringify(TagValue... tags) {
    String[] tagStrings = new String[tags.length];
    for (int i = 0; i < tags.length; i++) {
      tagStrings[i] = tags[i].asString();
    }
    return tagStrings;
  }
}
