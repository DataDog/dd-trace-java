package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDId;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.interceptor.MutableSpan;
import java.util.Map;

public interface AgentSpan extends MutableSpan {

  DDId getTraceId();

  DDId getSpanId();

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

  /**
   * Sets the span checkpoint emission state
   *
   * @param value {@literal true} to enable checkpoint emission, {@literal false} otherwise
   */
  void setEmittingCheckpoints(boolean value);

  /**
   * Checks the span checkpoint emission state
   *
   * @return a {@literal true/false} value or {@literal null} if the state needs to be set yet
   */
  Boolean isEmittingCheckpoints();

  @Override
  AgentSpan getLocalRootSpan();

  boolean isSameTrace(AgentSpan otherSpan);

  Context context();

  String getBaggageItem(String key);

  AgentSpan setBaggageItem(String key, String value);

  AgentSpan setHttpStatusCode(int statusCode);

  short getHttpStatusCode();

  void finish();

  void finish(long finishMicros);

  /**
   * Finishes the span but does not publish it. The {@link #publish()} method MUST be called once
   * otherwise the trace will not be reported.
   *
   * @return true if the span was successfully finished, false if it was already finished.
   */
  boolean phasedFinish();

  /**
   * Publish a span that was previously finished by calling {@link #phasedFinish()}. Must be called
   * once and only once per span.
   */
  void publish();

  CharSequence getSpanName();

  void setSpanName(CharSequence spanName);

  boolean hasResourceName();

  @Override
  AgentSpan setResourceName(final CharSequence resourceName);

  boolean eligibleForDropping();

  /** mark that the span has been captured in some task which will resume asynchronously. */
  void startThreadMigration();

  /** mark that the work associated with the span has resumed on a new thread */
  void finishThreadMigration();

  /** Mark the end of a task associated with the span */
  void finishWork();

  /** RequestContext for the Instrumentation Gateway */
  RequestContext getRequestContext();

  interface Context {
    DDId getTraceId();

    DDId getSpanId();

    AgentTrace getTrace();

    Iterable<Map.Entry<String, String>> baggageItems();

    /** RequestContext for the Instrumentation Gateway */
    RequestContext getRequestContext();

    interface Extracted extends Context {
      String getForwarded();

      String getForwardedProto();

      String getForwardedHost();

      String getForwardedIp();

      String getForwardedPort();
    }
  }
}
