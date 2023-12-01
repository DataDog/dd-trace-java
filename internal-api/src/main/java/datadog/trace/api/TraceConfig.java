package datadog.trace.api;

import datadog.trace.api.sampling.SamplingRule.SpanSamplingRule;
import datadog.trace.api.sampling.SamplingRule.TraceSamplingRule;
import java.util.List;
import java.util.Map;

/** Snapshot of dynamic configuration; valid for the duration of a trace. */
public interface TraceConfig {

  boolean isDebugEnabled();

  boolean isTriageEnabled();

  boolean isRuntimeMetricsEnabled();

  boolean isLogsInjectionEnabled();

  boolean isDataStreamsEnabled();

  Map<String, String> getServiceMapping();

  Map<String, String> getRequestHeaderTags();

  Map<String, String> getResponseHeaderTags();

  Map<String, String> getBaggageMapping();

  Double getTraceSampleRate();

  /**
   * Get the tracer sampler span sampling rules.
   *
   * @return The tracer sampler span sampling rules, or an empty collection if no rule is defined.
   */
  List<? extends SpanSamplingRule> getSpanSamplingRules();

  /**
   * Get the tracer sampler trace sampling rules.
   *
   * @return The tracer sampler trace sampling rules, or an empty collection if no rule is defined.
   */
  List<? extends TraceSamplingRule> getTraceSamplingRules();
}
