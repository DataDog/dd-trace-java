package datadog.trace.api;

import java.util.Map;

/** Snapshot of dynamic configuration; valid for the duration of a trace. */
public interface TraceConfig {

  boolean isDebugEnabled();

  boolean isRuntimeMetricsEnabled();

  boolean isLogsInjectionEnabled();

  boolean isDataStreamsEnabled();

  Map<String, String> getServiceMapping();

  Map<String, String> getRequestHeaderTags();

  Map<String, String> getResponseHeaderTags();

  Map<String, String> getBaggageMapping();

  Double getTraceSampleRate();
}
