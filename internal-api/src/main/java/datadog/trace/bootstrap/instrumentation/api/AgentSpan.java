package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.interceptor.MutableSpan;
import java.util.Map;

public interface AgentSpan extends MutableSpan, IGSpanInfo, WithAgentSpan {

  DDTraceId getTraceId();

  long getSpanId();

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

  AgentSpan setError(boolean error, byte priority);

  AgentSpan setMeasured(boolean measured);

  AgentSpan setErrorMessage(String errorMessage);

  AgentSpan addThrowable(Throwable throwable);

  AgentSpan addThrowable(Throwable throwable, byte errorPriority);

  @Override
  AgentSpan getLocalRootSpan();

  boolean isSameTrace(AgentSpan otherSpan);

  AgentSpanContext context();

  String getBaggageItem(String key);

  AgentSpan setBaggageItem(String key, String value);

  AgentSpan setHttpStatusCode(int statusCode);

  short getHttpStatusCode();

  void finish();

  void finish(long finishMicros);

  void finishWithDuration(long durationNanos);

  /** Marks the start of a message pipeline where we want to track end-to-end processing time. */
  void beginEndToEnd();

  /**
   * Marks the end of a message pipeline where the final end-to-end processing time is recorded.
   *
   * <p>Note: this will also finish the span and publish it.
   */
  void finishWithEndToEnd();

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

  /**
   * Deprecated in favor of setResourceName(final CharSequence resourceName, byte priority) or using
   * getResourceNamePriority() for comparisons.
   */
  @Deprecated
  boolean hasResourceName();

  byte getResourceNamePriority();

  @Override
  AgentSpan setResourceName(final CharSequence resourceName);

  /**
   * Implementation note: two calls with the same priority will result in the second resource name
   * being used
   */
  AgentSpan setResourceName(final CharSequence resourceName, byte priority);

  boolean eligibleForDropping();

  /** RequestContext for the Instrumentation Gateway */
  RequestContext getRequestContext();

  Integer forceSamplingDecision();

  AgentSpan setSamplingPriority(final int newPriority, int samplingMechanism);

  TraceConfig traceConfig();

  void addLink(AgentSpanLink link);

  AgentSpan setMetaStruct(final String field, final Object value);

  boolean isOutbound();

  default AgentSpan asAgentSpan() {
    return this;
  }

  interface Attributes {
    /**
     * Gets the attributes as an immutable map.
     *
     * @return The attributes as an immutable map.
     */
    Map<String, String> asMap();

    /**
     * Checks whether the attributes are empty.
     *
     * @return {@code true} if the attributes are empty, {@code false} otherwise.
     */
    boolean isEmpty();
  }
}
