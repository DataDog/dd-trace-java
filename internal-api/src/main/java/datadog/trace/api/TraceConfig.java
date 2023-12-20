package datadog.trace.api;

import datadog.trace.api.sampling.SamplingRule.SpanSamplingRule;
import datadog.trace.api.sampling.SamplingRule.TraceSamplingRule;
import java.util.List;
import java.util.Map;

/** Snapshot of dynamic configuration; valid for the duration of a trace. */
public interface TraceConfig {

  boolean isRuntimeMetricsEnabled();

  boolean isLogsInjectionEnabled();

  boolean isDataStreamsEnabled();

  Map<String, String> getServiceMapping();

  Map<String, String> getRequestHeaderTags();

  Map<String, String> getResponseHeaderTags();

  Map<String, String> getBaggageMapping();

  Double getTraceSampleRate();

  /**
   * Get the tracer sampler Span Sampling Rules.
   *
   * @return The tracer sampler Span Sampling Rules, or an empty collection if no rule is defined.
   */
  List<? extends SpanSamplingRule> getSpanSamplingRules();

  /**
   * Get the tracer sampler Trace Sampling Rules.
   *
   * @return The tracer sampler Trace Sampling Rules, or an empty collection if no rule is defined.
   */
  List<? extends TraceSamplingRule> getTraceSamplingRules();
}
