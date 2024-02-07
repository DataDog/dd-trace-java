package datadog.trace.api.civisibility.telemetry;

import datadog.trace.api.telemetry.MetricCollector;

public class CiVisibilityMetricData extends MetricCollector.Metric {

  enum Type {
    COUNT,
    DISTRIBUTION;

    private final String typeName;

    Type() {
      this.typeName = name().toLowerCase();
    }

    public String getTypeName() {
      return typeName;
    }
  }

  private static final String NAMESPACE = "civisibility";

  CiVisibilityMetricData(String metricName, Type type, long counter, TagValue... tags) {
    super(NAMESPACE, true, metricName, type.getTypeName(), counter, stringify(tags));
  }

  private static String[] stringify(TagValue... tags) {
    String[] tagStrings = new String[tags.length];
    for (int i = 0; i < tags.length; i++) {
      tagStrings[i] = tags[i].asString();
    }
    return tagStrings;
  }
}
