package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDId;
import datadog.trace.api.interceptor.MutableSpan;

public interface AgentSpan extends MutableSpan {

  DDId getTraceId();

  @Override
  AgentSpan setTag(String key, boolean value);

  AgentSpan setTag(String key, int value);

  AgentSpan setTag(String key, long value);

  AgentSpan setTag(String key, double value);

  @Override
  AgentSpan setTag(String key, String value);

  @Override
  AgentSpan setError(boolean error);

  AgentSpan setErrorMessage(String errorMessage);

  AgentSpan addThrowable(Throwable throwable);

  @Override
  AgentSpan getLocalRootSpan();

  boolean isSameTrace(AgentSpan otherSpan);

  Context context();

  void finish();

  String getSpanName();

  void setSpanName(String spanName);

  boolean hasResourceName();

  interface Context {
    AgentTrace getTrace();
  }
}
