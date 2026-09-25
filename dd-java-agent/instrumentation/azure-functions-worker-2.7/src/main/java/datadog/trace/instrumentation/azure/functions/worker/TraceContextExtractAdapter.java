package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;

import com.microsoft.azure.functions.TraceContext;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public final class TraceContextExtractAdapter
    implements AgentPropagation.ContextVisitor<TraceContext> {
  public static final TraceContextExtractAdapter GETTER = new TraceContextExtractAdapter();

  private static final String TRACEPARENT = "traceparent";
  private static final String TRACESTATE = "tracestate";

  private TraceContextExtractAdapter() {}

  @Override
  public void forEachKey(TraceContext carrier, AgentPropagation.KeyClassifier classifier) {
    final String tracestate = carrier.getTracestate();
    String traceparent = carrier.getTraceparent();
    final int datadogSamplingPriority = datadogSamplingPriority(tracestate);
    if (datadogSamplingPriority == UNSET || datadogSamplingPriority > 0) {
      // The Azure host can clear the sampled flag when host telemetry is disabled even though the
      // trace identifiers are intended to correlate durable worker invocations. Restore the flag
      // only in this durable-trigger extraction path, while preserving explicit Datadog drops.
      traceparent = setSampledFlag(traceparent);
    }
    if (traceparent != null && !classifier.accept(TRACEPARENT, traceparent)) {
      return;
    }
    if (tracestate != null) {
      classifier.accept(TRACESTATE, tracestate);
    }
  }

  static int datadogSamplingPriority(String tracestate) {
    if (tracestate == null) {
      return UNSET;
    }
    int memberStart = 0;
    while (memberStart < tracestate.length()) {
      int memberEnd = tracestate.indexOf(',', memberStart);
      if (memberEnd < 0) {
        memberEnd = tracestate.length();
      }
      int start = memberStart;
      while (start < memberEnd && isOptionalWhitespace(tracestate.charAt(start))) {
        start++;
      }
      int end = memberEnd;
      while (end > start && isOptionalWhitespace(tracestate.charAt(end - 1))) {
        end--;
      }
      if (end - start >= 3
          && tracestate.charAt(start) == 'd'
          && tracestate.charAt(start + 1) == 'd'
          && tracestate.charAt(start + 2) == '=') {
        return parseSamplingPriority(tracestate, start + 3, end);
      }
      memberStart = memberEnd + 1;
    }
    return UNSET;
  }

  private static boolean isOptionalWhitespace(char value) {
    return value == ' ' || value == '\t';
  }

  private static int parseSamplingPriority(String value, int start, int end) {
    while (start < end) {
      int fieldEnd = value.indexOf(';', start);
      if (fieldEnd < 0 || fieldEnd > end) {
        fieldEnd = end;
      }
      if (fieldEnd - start > 2 && value.charAt(start) == 's' && value.charAt(start + 1) == ':') {
        try {
          return Integer.parseInt(value.substring(start + 2, fieldEnd));
        } catch (NumberFormatException ignored) {
          return UNSET;
        }
      }
      start = fieldEnd + 1;
    }
    return UNSET;
  }

  private static String setSampledFlag(String traceparent) {
    if (traceparent == null || traceparent.length() < 55) {
      return traceparent;
    }
    final int high = Character.digit(traceparent.charAt(53), 16);
    final int low = Character.digit(traceparent.charAt(54), 16);
    if (high < 0 || low < 0 || (low & 1) != 0) {
      return traceparent;
    }
    final char sampledLow = Character.forDigit(low | 1, 16);
    return traceparent.substring(0, 54) + sampledLow + traceparent.substring(55);
  }
}
