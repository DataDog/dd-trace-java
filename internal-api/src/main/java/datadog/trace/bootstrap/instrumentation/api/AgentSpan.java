package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDId;
import datadog.trace.api.interceptor.MutableSpan;
import java.util.Map;

public interface AgentSpan extends MutableSpan {

  DDId getTraceId();

  @Override
  AgentSpan setTag(String key, boolean value);

  AgentSpan setTag(String key, int value);

  AgentSpan setTag(String key, long value);

  AgentSpan setTag(String key, double value);

  @Override
  AgentSpan setTag(String key, String value);

  AgentSpan setTag(String key, CharSequence value);

  AgentSpan setTag(String key, Object value);

  @Override
  AgentSpan setTag(String key, Number value);

  @Override
  AgentSpan setMetric(CharSequence key, int value);

  @Override
  AgentSpan setMetric(CharSequence key, long value);

  @Override
  AgentSpan setMetric(CharSequence key, double value);

  @Override
  AgentSpan setSpanType(final CharSequence type);

  Object getTag(String key);

  @Override
  AgentSpan setError(boolean error);

  AgentSpan setMeasured(boolean measured);

  AgentSpan setErrorMessage(String errorMessage);

  AgentSpan addThrowable(Throwable throwable);

  @Override
  AgentSpan getLocalRootSpan();

  boolean isSameTrace(AgentSpan otherSpan);

  Context context();

  String getBaggageItem(String key);

  AgentSpan setBaggageItem(String key, String value);

  void finish();

  void finish(long finishMicros);

  CharSequence getSpanName();

  void setSpanName(CharSequence spanName);

  boolean hasResourceName();

  @Override
  AgentSpan setResourceName(final CharSequence resourceName);

  boolean eligibleForDropping();

  interface Context {
    DDId getTraceId();

    DDId getSpanId();

    AgentTrace getTrace();

    Iterable<Map.Entry<String, String>> baggageItems();

    interface Extracted extends Context {
      String getForwardedFor();

      String getForwardedPort();
    }
  }
}
