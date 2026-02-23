package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.bootstrap.instrumentation.api.InternalContextKeys.SPAN_KEY;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.context.ImplicitContextKeyed;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.interceptor.MutableSpan;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface AgentSpan
    extends MutableSpan, ImplicitContextKeyed, Context, IGSpanInfo, WithAgentSpan {

  /**
   * Extracts the span from context.
   *
   * @param context the context to extract the span from.
   * @return the span if existing, {@code null} otherwise.
   */
  static AgentSpan fromContext(Context context) {
    return context == null ? null : context.get(SPAN_KEY);
  }

  /**
   * Creates a span wrapper from a span context.
   *
   * <p>Creating such span will not create a tracing span to complete a local root trace. It gives a
   * span instance based on a span context for span-based API. It is usually used with an extracted
   * span context as parameter to represent a remote span.
   *
   * @param spanContext the span context to get a full-fledged span.
   * @return a span wrapper based on a span context.
   */
  static AgentSpan fromSpanContext(AgentSpanContext spanContext) {
    if (spanContext == null || spanContext == NoopSpanContext.INSTANCE) {
      return NoopSpan.INSTANCE;
    }
    return new ExtractedSpan(spanContext);
  }

  DDTraceId getTraceId();

  long getSpanId();

  /**
   * Checks whether a span is considered valid by having valid trace and span identifiers.
   *
   * @return {@code true} if the span is considered valid, {@code false} otherwise.
   */
  default boolean isValid() {
    return getTraceId() != DDTraceId.ZERO && getSpanId() != DDSpanId.ZERO;
  }

  @Override
  AgentSpan setTag(String key, boolean value);

  AgentSpan setTag(String key, int value);

  AgentSpan setTag(String key, long value);

  AgentSpan setTag(String key, float value);

  AgentSpan setTag(String key, double value);

  @Override
  AgentSpan setTag(String key, String value);

  AgentSpan setTag(String key, CharSequence value);

  AgentSpan setTag(String key, Object value);

  /** entry may be null - in which case the tags remained unchanged */
  AgentSpan setTag(TagMap.EntryReader entry);

  AgentSpan setAllTags(Map<String, ?> map);

  @Override
  AgentSpan setTag(String key, Number value);

  @Override
  AgentSpan setMetric(CharSequence key, int value);

  @Override
  AgentSpan setMetric(CharSequence key, long value);

  @Override
  AgentSpan setMetric(CharSequence key, float value);

  @Override
  AgentSpan setMetric(CharSequence key, double value);

  /** metricEntry may be null - in which case the tags remained unchanged */
  AgentSpan setMetric(TagMap.EntryReader metricEntry);

  @Override
  AgentSpan setSpanType(final CharSequence type);

  @Override
  TagMap getTags();

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

  default void copyPropagationAndBaggage(final AgentSpan source) {
    // no op default
  }

  @Override
  default Context storeInto(@Nonnull Context context) {
    return context == Context.root() ? this : context.with(SPAN_KEY, this);
  }

  @Nullable
  @Override
  default <T> T get(@Nonnull ContextKey<T> key) {
    // noinspection unchecked
    return SPAN_KEY == key ? (T) this : Context.root().get(key);
  }

  @Override
  default <T> Context with(@Nonnull ContextKey<T> key, @Nullable T value) {
    return SPAN_KEY == key ? (Context) value : Context.root().with(SPAN_KEY, this, key, value);
  }

  /**
   * Attaches a context containing just the span to the current execution unit. Use this when you
   * want to temporarily suppress any surrounding custom context during the span's scope.
   *
   * @return a scope to be closed when the span context is invalid.
   */
  @Override
  default ContextScope attach() {
    return Context.super.attach();
  }

  /**
   * Attaches a context combining the span with the current context to the current execution unit.
   * Use this when you want to maintain any surrounding custom context during the span's scope.
   *
   * @return a scope to be closed when the combined context is invalid.
   */
  default ContextScope attachWithCurrent() {
    return storeInto(Context.current()).attach();
  }

  /**
   * Sets the service name without tracking it's source.
   *
   * <p>Note: please use setServiceName(String, CharSequence) instead.
   *
   * @param serviceName the service name.
   * @return the span itself.
   */
  @Override
  @Deprecated
  MutableSpan setServiceName(final String serviceName);

  /**
   * Set the service name specifying the source (origin) of this name
   *
   * @param serviceName the service name
   * @param source the source. Can be typically the name of the integration that overrides the
   *     default name.
   */
  default void setServiceName(@Nonnull String serviceName, @Nonnull CharSequence source) {
    setServiceName(serviceName);
  }
}
