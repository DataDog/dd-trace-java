package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import okio.Okio;

public class ProbeDefinitionDeserializer {
  static final JsonAdapter<Configuration> CONFIGURATION_JSON_ADAPTER =
      MoshiHelper.createMoshiConfig().adapter(Configuration.class);
  static final JsonAdapter<MetricProbe> METRIC_PROBE_JSON_ADAPTER =
      MoshiHelper.createMoshiConfig().adapter(MetricProbe.class);
  static final JsonAdapter<LogProbe> LOG_PROBE_JSON_ADAPTER =
      MoshiHelper.createMoshiConfig().adapter(LogProbe.class);
  static final JsonAdapter<SpanProbe> SPAN_PROBE_JSON_ADAPTER =
      MoshiHelper.createMoshiConfig().adapter(SpanProbe.class);
  static final JsonAdapter<TriggerProbe> TRIGGER_PROBE_JSON_ADAPTER =
      MoshiHelper.createMoshiConfig().adapter(TriggerProbe.class);
  static final JsonAdapter<SpanDecorationProbe> SPAN_DECORATION_PROBE_JSON_ADAPTER =
      MoshiHelper.createMoshiConfig().adapter(SpanDecorationProbe.class);

  public static Configuration deserializeConfiguration(byte[] content) throws IOException {
    return deserialize(CONFIGURATION_JSON_ADAPTER, content);
  }

  public static MetricProbe deserializeMetricProbe(byte[] content) throws IOException {
    return deserialize(METRIC_PROBE_JSON_ADAPTER, content);
  }

  public static LogProbe deserializeLogProbe(byte[] content) throws IOException {
    LogProbe logProbe = deserialize(LOG_PROBE_JSON_ADAPTER, content);
    logProbe.initSamplers();
    return logProbe;
  }

  public static SpanProbe deserializeSpanProbe(byte[] content) throws IOException {
    return deserialize(SPAN_PROBE_JSON_ADAPTER, content);
  }

  public static TriggerProbe deserializeTriggerProbe(byte[] content) throws IOException {
    TriggerProbe triggerProbe = deserialize(TRIGGER_PROBE_JSON_ADAPTER, content);
    triggerProbe.initSamplers();
    return triggerProbe;
  }

  public static SpanDecorationProbe deserializeSpanDecorationProbe(byte[] content)
      throws IOException {
    SpanDecorationProbe spanDecorationProbe =
        deserialize(SPAN_DECORATION_PROBE_JSON_ADAPTER, content);
    spanDecorationProbe.initSamplers();
    return spanDecorationProbe;
  }

  private static <T> T deserialize(JsonAdapter<T> adapter, byte[] content) throws IOException {
    return adapter.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
  }
}
