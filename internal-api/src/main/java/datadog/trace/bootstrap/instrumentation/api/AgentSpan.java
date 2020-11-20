package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDId;
import datadog.trace.api.interceptor.MutableSpan;
import java.util.Map;

/**
 * This interface represents a union of AgentSpanData and MutableSpan, plus some additional methods
 */
public interface AgentSpan<SPAN extends AgentSpan<SPAN>> extends AgentSpanData, MutableSpan<SPAN> {

  SPAN setTag(String key, int value);

  SPAN setTag(String key, long value);

  SPAN setTag(String key, double value);

  SPAN setTag(String key, CharSequence value);

  SPAN setTag(String key, Object value);

  SPAN setMetric(CharSequence key, float value);

  SPAN setFlag(CharSequence name, boolean value);

  SPAN setSamplingPriority(int samplingPriority, CharSequence rate, double sampleRate);

  SPAN setMeasured(boolean measured);

  SPAN setErrorMessage(String errorMessage);

  SPAN addThrowable(Throwable throwable);

  String getBaggageItem(String key);

  SPAN setBaggageItem(String key, String value);

  void finish();

  void finish(long finishMicros);

  boolean isSameTrace(AgentSpan<?> otherSpan);

  boolean hasResourceName();

  Context context();

  interface Context {
    DDId getTraceId();

    DDId getSpanId();

    AgentTrace getTrace();

    Iterable<Map.Entry<String, String>> baggageItems();
  }
}
