package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDId;
import datadog.trace.api.interceptor.MutableSpan;

import java.util.Map;

public interface AgentSpan extends MutableSpan {

  DDId getTraceId();

  DDId getSpanId();

  DDId getParentId();

  @Override
  AgentSpan setTag(String key, boolean value);

  AgentSpan setTag(String key, int value);

  AgentSpan setTag(String key, long value);

  AgentSpan setTag(String key, double value);

  @Override
  AgentSpan setTag(String key, String value);

  AgentSpan setTag(String key, Object value);

  @Override
  AgentSpan setError(boolean error);

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

  String getSpanName();

  void setSpanName(String spanName);

  boolean hasResourceName();

  interface Context {
    DDId getTraceId();

    DDId getSpanId();

    AgentTrace getTrace();

    Iterable<Map.Entry<String, String>> baggageItems();
  }
}
