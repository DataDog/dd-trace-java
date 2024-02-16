package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.sampling.PrioritySampling;
import java.util.List;
import java.util.Map;

public interface AgentSpan extends MutableSpan, IGSpanInfo, ImplicitContextKeyed {

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

  Context context();

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

  @Override
  default ScopedContext storeInto(ScopedContext context) {
    return context.with(ScopedContextKey.SPAN_KEY, this);
  }

  interface Context {
    /**
     * Gets the TraceId of the span's trace.
     *
     * @return The TraceId of the span's trace, or {@link DDTraceId#ZERO} if not set.
     */
    DDTraceId getTraceId();

    /**
     * Gets the SpanId.
     *
     * @return The span identifier, or {@link datadog.trace.api.DDSpanId#ZERO} if not set.
     */
    long getSpanId();

    /**
     * Get the span's trace.
     *
     * @return The span's trace, or a noop {@link AgentTracer.NoopAgentTrace#INSTANCE} if the trace
     *     is not valid.
     */
    AgentTrace getTrace();

    /**
     * Gets the trace sampling priority of the span's trace.
     *
     * <p>Check {@link PrioritySampling} for possible values.
     *
     * @return The trace sampling priority of the span's trace, or {@link PrioritySampling#UNSET} if
     *     no priority has been set.
     */
    int getSamplingPriority();

    Iterable<Map.Entry<String, String>> baggageItems();

    PathwayContext getPathwayContext();

    default void mergePathwayContext(PathwayContext pathwayContext) {}

    interface Extracted extends Context {
      /**
       * Gets the span links related to the other terminated context.
       *
       * @return The span links to other extracted contexts found but terminated.
       */
      List<AgentSpanLink> getTerminatedContextLinks();

      String getForwarded();

      String getFastlyClientIp();

      String getCfConnectingIp();

      String getCfConnectingIpv6();

      String getXForwardedProto();

      String getXForwardedHost();

      String getXForwardedPort();

      String getForwardedFor();

      String getXForwarded();

      String getXForwardedFor();

      String getXClusterClientIp();

      String getXRealIp();

      String getXClientIp();

      String getUserAgent();

      String getTrueClientIp();

      String getCustomIpHeader();
    }
  }
}
