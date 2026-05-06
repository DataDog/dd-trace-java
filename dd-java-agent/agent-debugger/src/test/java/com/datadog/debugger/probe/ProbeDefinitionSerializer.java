package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Configuration;

public class ProbeDefinitionSerializer {

  public static String serializeMetricProbe(MetricProbe metricProbe) {
    return ProbeDefinitionDeserializer.METRIC_PROBE_JSON_ADAPTER.toJson(metricProbe);
  }

  public static String serializeLogProbe(LogProbe logProbe) {
    return ProbeDefinitionDeserializer.LOG_PROBE_JSON_ADAPTER.toJson(logProbe);
  }

  public static String serializeSpanProbe(SpanProbe spanProbe) {
    return ProbeDefinitionDeserializer.SPAN_PROBE_JSON_ADAPTER.toJson(spanProbe);
  }

  public static String serializeSpanDecorationProbe(SpanDecorationProbe spanDecorationProbe) {
    return ProbeDefinitionDeserializer.SPAN_DECORATION_PROBE_JSON_ADAPTER.toJson(
        spanDecorationProbe);
  }

  public static String serializeTriggerProbe(TriggerProbe triggerProbe) {
    return ProbeDefinitionDeserializer.TRIGGER_PROBE_JSON_ADAPTER.toJson(triggerProbe);
  }

  public static String serializeConfiguration(Configuration configuration) {
    return ProbeDefinitionDeserializer.CONFIGURATION_JSON_ADAPTER.toJson(configuration);
  }
}
