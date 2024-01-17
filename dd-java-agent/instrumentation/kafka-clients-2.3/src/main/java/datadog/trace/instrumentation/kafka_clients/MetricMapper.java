package datadog.trace.instrumentation.kafka_clients;

import java.util.Map;

public class MetricMapper {
  final String originMetricName;
  final String destinationMetricName;
  final Map<String, String> tagMap;

  public MetricMapper(String originMetricName, String destinationMetricName, Map<String, String> tagMap) {
    this.originMetricName = originMetricName;
    this.destinationMetricName = destinationMetricName;
    this.tagMap = tagMap;
  }
}
