package datadog.trace.api;

import java.util.Map;

/** Snapshot of dynamic configuration; valid for the duration of a trace. */
public interface TraceConfig {

  Map<String, String> getServiceMapping();

  Map<String, String> getHeaderTags();

  Map<String, String> getResponseHeaderTags();

  Map<String, String> getBaggageMapping();

  boolean isLogsInjectionEnabled();
}
