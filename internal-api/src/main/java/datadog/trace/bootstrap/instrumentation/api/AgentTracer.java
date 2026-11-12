package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextHandle;
import datadog.context.ContextListener;
import datadog.context.ContextManager;
import datadog.context.ContextScope;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Pair;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.api.datastreams.NoopDataStreamsMonitoring;
import datadog.trace.api.experimental.DataStreamsCheckpointer;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.internal.InternalTracer;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.sampling.SamplingRule;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.context.NoopTraceScope;
import datadog.trace.context.TraceScope;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

public class AgentTracer {

  /**
   * @see TracerAPI#startSpan(String, CharSequence)
   */
  public static AgentSpan startSpan(final String instrumentationName, final CharSequence spanName) {
    return get().startSpan(instrumentationName, spanName);
  }

  /**
   * @see TracerAPI#startSpan(String, CharSequence, long)
   */
  public static AgentSpan startSpan(
      final String instrumentationName, final CharSequence spanName, final long startTimeMicros) {
    return get().startSpan(instrumentationName, spanName, startTimeMicros);
  }

  /**
   * @see TracerAPI#startSpan(String, CharSequence, AgentSpanContext)
   */
  public static AgentSpan startSpan(
      final String instrumentationName,
      final CharSequence spanName,
      final AgentSpanContext parent) {
    return get().startSpan(instrumentationName, spanName, parent);
  }

  /**
   * @see TracerAPI#startSpan(String, CharSequence, AgentSpanContext, long)
   */
  public static AgentSpan startSpan(
      final String instrumentationName,
      final CharSequence spanName,
      final AgentSpanContext parent,
      final long startTimeMicros) {
    return get().startSpan(instrumentationName, spanName, parent, startTimeMicros);
  }

  /**
   * @see TracerAPI#startSpan(SpanPrototype, CharSequence)
   */
  public static AgentSpan startSpan(
      @Nonnull final SpanPrototype prototype, final CharSequence operationName) {
    return get().startSpan(prototype, operationName);
  }

  public static ContextScope activateSpan(final AgentSpan span) {
    return get().activateSpan(span);
  }

  public static AgentSpan activeSpan() {
    return get().activeSpan();
  }

  /**
   * Activate a span which will be closed by {@link #closeActive()} instead of a scope.
   *
   * @deprecated This should only be used when the instrumented code doesn't align with a scope.
   */
  @Deprecated
  public static void activateSpanWithoutScope(final AgentSpan span) {
    get().activateSpanWithoutScope(span);
  }

  /**
   * Closes the scope for the currently active span.
   *
   * @deprecated This should only be used when the span was previously activated with {@link
   *     #activateSpanWithoutScope} because the instrumented code didn't align with a scope.
   */
  @Deprecated
  public static void closeActive() {
    get().closeActive();
  }

  public static TraceConfig traceConfig(final AgentSpan span) {
    return null != span ? span.traceConfig() : traceConfig();
  }

  public static TraceConfig traceConfig() {
    return get().captureTraceConfig();
  }

  /**
   * Returns the noop span instance.
   *
   * <p>This instance will always be the same, and can be safely tested using object identity (ie
   * {@code ==}).
   *
   * @return the noop span instance.
   */
  public static AgentSpan noopSpan() {
    return NoopSpan.INSTANCE;
  }

  public static AgentSpan blackholeSpan() {
    return get().blackholeSpan();
  }

  /**
   * Returns the noop span context instance.
   *
   * <p>This instance will always be the same, and can be safely tested using object identity (ie
   * {@code ==}).
   *
   * @return the noop scope instance.
   */
  public static AgentSpanContext noopSpanContext() {
    return NoopSpanContext.INSTANCE;
  }

  public static final TracerAPI NOOP_TRACER = new NoopTracerAPI();

  private static volatile TracerAPI provider = NOOP_TRACER;

  public static boolean isRegistered() {
    return provider != NOOP_TRACER;
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification = "Agent-internal static holder; class lock guards private static provider")
  public static synchronized void registerIfAbsent(final TracerAPI tracer) {
    if (tracer != null && tracer != NOOP_TRACER) {
      provider = tracer;
    }
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification = "Agent-internal static holder; class lock guards private static provider")
  public static synchronized void forceRegister(TracerAPI tracer) {
    if (tracer == null) {
      throw new IllegalArgumentException("tracer must not be null, use NOOP_TRACER instead");
    }
    provider = tracer;
  }

  public static TracerAPI get() {
    return provider;
  }

  // Not intended to be constructed.
  private AgentTracer() {}

  public interface TracerAPI
      extends datadog.trace.api.Tracer, InternalTracer, EndpointCheckpointer {

    /**
     * Create and start a new span.
     *
     * @param instrumentationName The instrumentation creating the span.
     * @param spanName The span operation name.
     * @return The new started span.
     */
    AgentSpan startSpan(String instrumentationName, CharSequence spanName);

    /**
     * Create and start a new span with a given start time.
     *
     * @param instrumentationName The instrumentation creating the span.
     * @param spanName The span operation name.
     * @param startTimeMicros The span start time, in microseconds.
     * @return The new started span.
     */
    AgentSpan startSpan(String instrumentationName, CharSequence spanName, long startTimeMicros);

    /**
     * Create and start a new span with an explicit parent.
     *
     * @param instrumentationName The instrumentation creating the span.
     * @param spanName The span operation name.
     * @param parent The parent span context.
     * @return The new started span.
     */
    AgentSpan startSpan(String instrumentationName, CharSequence spanName, AgentSpanContext parent);

    /**
     * Create and start a new span with an explicit parent and a given start time.
     *
     * @param instrumentationName The instrumentation creating the span.
     * @param spanName The span operation name.
     * @param parent The parent span context.
     * @param startTimeMicros The span start time, in microseconds.
     * @return The new started span.
     */
    AgentSpan startSpan(
        String instrumentationName,
        CharSequence spanName,
        AgentSpanContext parent,
        long startTimeMicros);

    /**
     * Creates and starts a span seeded from a {@link SpanPrototype}. This is the
     * auto-instrumentation entry point mirroring {@link #startSpan(String, CharSequence)}; see
     * {@link #buildSpan(SpanPrototype, CharSequence)}.
     */
    default AgentSpan startSpan(@Nonnull SpanPrototype prototype, CharSequence operationName) {
      return buildSpan(prototype, operationName).start();
    }

    /**
     * Returns a SpanBuilder that can be used to produce multiple spans. To minimize overhead, use
     * of {@link #singleSpanBuilder(String, CharSequence)} is preferred when only a single span is
     * being built.
     */
    SpanBuilder buildSpan(String instrumentationName, CharSequence spanName);

    /**
     * Returns a SpanBuilder seeded from a {@link SpanPrototype}: the prototype supplies the
     * instrumentation name, span type, and constant tags. {@code operationName} overrides the
     * prototype's when non-null — the explicit value wins, the prototype is the fallback.
     *
     * <p>This default seeds identity only; a real tracer should override it to also seed the
     * prototype's tags (see {@code CoreTracer}). The no-op tracer discards tags, so identity-only
     * is correct there.
     */
    default SpanBuilder buildSpan(@Nonnull SpanPrototype prototype, CharSequence operationName) {
      return buildSpan(
          prototype.instrumentationName(),
          operationName != null ? operationName : prototype.operationName());
    }

    /**
     * Returns a SpanBuilder that can be used to produce one and only one span. By imposing the
     * single span creation limitation, this method is more efficient than {@link #buildSpan}
     */
    SpanBuilder singleSpanBuilder(String instrumentationName, CharSequence spanName);

    default AgentSpan blackholeSpan() {
      final AgentSpan active = activeSpan();
      return new BlackHoleSpan(active != null ? active.getTraceId() : DDTraceId.ZERO);
    }

    /**
     * Attach a scope listener to the global scope manager
     *
     * @param listener listener to attach
     */
    void addScopeListener(ScopeListener listener);

    SubscriptionService getSubscriptionService(RequestContextSlot slot);

    CallbackProvider getCallbackProvider(RequestContextSlot slot);

    CallbackProvider getUniversalCallbackProvider();

    AgentSpanContext notifyLambdaStart(Object event, String lambdaRequestId);

    void notifyExtensionEnd(AgentSpan span, Object result, boolean isError, String lambdaRequestId);

    void notifyAppSecEnd(AgentSpan span, Object result);

    AgentDataStreamsMonitoring getDataStreamsMonitoring();

    String getTraceId(AgentSpan span);

    String getSpanId(AgentSpan span);

    TraceConfig captureTraceConfig();

    ProfilingContextIntegration getProfilingContext();

    /**
     * Sets the new service name to be used as a default.
     *
     * @param serviceName The service name to use as default.
     * @param source the source which is setting it.
     */
    void updatePreferredServiceName(String serviceName, CharSequence source);

    void addShutdownListener(Runnable listener);

    void close();

    // internal scope methods which will soon be replaced with their Context equivalents

    /** Activate a span from inside auto-instrumentation. */
    ContextScope activateSpan(AgentSpan span);

    /** Activate a span from outside auto-instrumentation, i.e. a manual or custom span. */
    ContextScope activateManualSpan(AgentSpan span);

    AgentSpan activeSpan();

    /** Activate a span which will be closed by {@link #closeActive()} instead of a scope. */
    void activateSpanWithoutScope(AgentSpan span);

    void closeActive();

    // deprecated public Tracer API methods which we can't remove yet for compatibility reasons

    @Override
    default boolean isAsyncPropagationEnabled() {
      return Context.isCaptureEnabled();
    }

    @Override
    default void setAsyncPropagationEnabled(boolean asyncPropagationEnabled) {
      Context.setCaptureEnabled(asyncPropagationEnabled);
    }

    @Override
    default TraceScope.Continuation captureActiveSpan() {
      Context current = currentContext();
      if (AgentSpan.fromContext(current) == null) {
        return NoopTraceScope.NoopContinuation.INSTANCE;
      }
      // captureActiveSpan is deprecated; use a best-effort wrapper
      return new TraceScopeContinuationWrapper(capture(current));
    }

    // deprecated temporary methods only used for legacy context manager migration

    @Deprecated
    void checkpointActiveForRollback();

    @Deprecated
    void rollbackActiveToCheckpoint();

    @Deprecated
    void closePrevious(boolean finishSpan);

    @Deprecated
    ContextScope activateNext(AgentSpan span);

    @Deprecated
    Context currentContext();

    @Deprecated
    ContextScope attach(Context context);

    @Deprecated
    ContextHandle swap(ContextHandle context);

    @Deprecated
    ContextContinuation capture(Context context);

    @Deprecated
    boolean isCaptureEnabled();

    @Deprecated
    void setCaptureEnabled(boolean enabled);
  }

  public interface SpanBuilder {
    AgentSpan start();

    SpanBuilder asChildOf(AgentSpanContext toContext);

    SpanBuilder ignoreActiveSpan();

    SpanBuilder withTag(String key, String value);

    SpanBuilder withTag(String key, boolean value);

    SpanBuilder withTag(String key, Number value);

    SpanBuilder withTag(String tag, Object value);

    SpanBuilder withStartTimestamp(long microseconds);

    SpanBuilder withServiceName(String serviceName);

    SpanBuilder withResourceName(String resourceName);

    SpanBuilder withErrorFlag();

    SpanBuilder withSpanType(CharSequence spanType);

    <T> SpanBuilder withRequestContextData(RequestContextSlot slot, T data);

    SpanBuilder withLink(AgentSpanLink link);

    SpanBuilder withSpanId(long spanId);
  }

  static class NoopTracerAPI implements TracerAPI {

    protected NoopTracerAPI() {}

    @Override
    public AgentSpan startSpan(final String instrumentationName, final CharSequence spanName) {
      return NoopSpan.INSTANCE;
    }

    @Override
    public AgentSpan startSpan(
        @Nonnull final SpanPrototype prototype, final CharSequence operationName) {
      // The default routes through buildSpan(String,...), which is null on the noop tracer -> NPE.
      return NoopSpan.INSTANCE;
    }

    @Override
    public AgentSpan startSpan(
        final String instrumentationName, final CharSequence spanName, final long startTimeMicros) {
      return NoopSpan.INSTANCE;
    }

    @Override
    public AgentSpan startSpan(
        final String instrumentationName,
        final CharSequence spanName,
        final AgentSpanContext parent) {
      return NoopSpan.INSTANCE;
    }

    @Override
    public AgentSpan startSpan(
        final String instrumentationName,
        final CharSequence spanName,
        final AgentSpanContext parent,
        final long startTimeMicros) {
      return NoopSpan.INSTANCE;
    }

    @Override
    public ContextScope activateSpan(final AgentSpan span) {
      return NoopScope.INSTANCE;
    }

    @Override
    public ContextScope activateManualSpan(final AgentSpan span) {
      return NoopScope.INSTANCE;
    }

    @Override
    public void activateSpanWithoutScope(final AgentSpan span) {}

    @Override
    public void checkpointActiveForRollback() {}

    @Override
    public void rollbackActiveToCheckpoint() {}

    @Override
    public void closeActive() {}

    @Override
    public void closePrevious(final boolean finishSpan) {}

    @Override
    public ContextScope activateNext(final AgentSpan span) {
      return NoopScope.INSTANCE;
    }

    @Override
    public AgentSpan activeSpan() {
      return NoopSpan.INSTANCE;
    }

    @Override
    public AgentSpan blackholeSpan() {
      return NoopSpan.INSTANCE; // no-op tracer stays no-op
    }

    @Override
    public SpanBuilder buildSpan(final String instrumentationName, final CharSequence spanName) {
      return null;
    }

    @Override
    public SpanBuilder buildSpan(
        @Nonnull final SpanPrototype prototype, final CharSequence operationName) {
      // Mirrors buildSpan(String,...): the noop tracer returns a null builder. Callers that need a
      // noop-safe entry point use startSpan(...), which is overridden above. A chainable
      // NoopSpanBuilder would fix the null-vs-NoopSpan asymmetry, but that is a separate PR.
      return null;
    }

    @Override
    public SpanBuilder singleSpanBuilder(
        final String instrumentationName, final CharSequence spanName) {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public void addScopeListener(
        Runnable afterScopeActivatedCallback, Runnable afterScopeClosedCallback) {}

    @Override
    public void flush() {}

    @Override
    public void flushMetrics() {}

    @Override
    public void flushLogs() {}

    @Override
    public ProfilingContextIntegration getProfilingContext() {
      return ProfilingContextIntegration.NoOp.INSTANCE;
    }

    @Override
    public TraceSegment getTraceSegment() {
      return null;
    }

    @Override
    public String getTraceId() {
      return null;
    }

    @Override
    public String getSpanId() {
      return null;
    }

    @Override
    public String getTraceId(AgentSpan span) {
      return null;
    }

    @Override
    public String getSpanId(AgentSpan span) {
      return null;
    }

    @Override
    public boolean addTraceInterceptor(final TraceInterceptor traceInterceptor) {
      return false;
    }

    @Override
    public TraceScope muteTracing() {
      return NoopTraceScope.INSTANCE;
    }

    @Override
    public DataStreamsCheckpointer getDataStreamsCheckpointer() {
      return getDataStreamsMonitoring();
    }

    @Override
    public void addShutdownListener(Runnable listener) {}

    @Override
    public void addScopeListener(final ScopeListener listener) {}

    @Override
    public SubscriptionService getSubscriptionService(RequestContextSlot slot) {
      return SubscriptionService.SubscriptionServiceNoop.INSTANCE;
    }

    @Override
    public CallbackProvider getCallbackProvider(RequestContextSlot slot) {
      return CallbackProvider.CallbackProviderNoop.INSTANCE;
    }

    @Override
    public CallbackProvider getUniversalCallbackProvider() {
      return CallbackProvider.CallbackProviderNoop.INSTANCE;
    }

    @Override
    public void onRootSpanFinished(AgentSpan root, EndpointTracker tracker) {}

    @Override
    public EndpointTracker onRootSpanStarted(AgentSpan root) {
      return EndpointTracker.NO_OP;
    }

    @Override
    public AgentSpanContext notifyLambdaStart(Object event, String lambdaRequestId) {
      return null;
    }

    @Override
    public void notifyExtensionEnd(
        AgentSpan span, Object result, boolean isError, String lambdaRequestId) {}

    @Override
    public void notifyAppSecEnd(AgentSpan span, Object result) {}

    @Override
    public AgentDataStreamsMonitoring getDataStreamsMonitoring() {
      return NoopDataStreamsMonitoring.INSTANCE;
    }

    @Override
    public TraceConfig captureTraceConfig() {
      return NoopTraceConfig.INSTANCE;
    }

    @Override
    public void updatePreferredServiceName(String serviceName, CharSequence preferredServiceName) {
      // no ops
    }

    @Override
    public Context currentContext() {
      return Context.root();
    }

    @Override
    public ContextScope attach(Context context) {
      return NoopScope.INSTANCE;
    }

    @Override
    public ContextHandle swap(ContextHandle context) {
      return Context.root();
    }

    @Override
    public ContextContinuation capture(Context context) {
      return NoopContinuation.INSTANCE;
    }

    @Override
    public boolean isCaptureEnabled() {
      return false;
    }

    @Override
    public void setCaptureEnabled(boolean enabled) {}
  }

  public static class NoopAgentTraceCollector implements AgentTraceCollector {
    public static final NoopAgentTraceCollector INSTANCE = new NoopAgentTraceCollector();

    @Override
    public void registerContinuation(final ContextContinuation continuation) {}

    @Override
    public void removeContinuation(final ContextContinuation continuation) {}
  }

  /** TraceConfig when there is no tracer; this is not the same as a default config. */
  public static final class NoopTraceConfig implements TraceConfig {
    public static final NoopTraceConfig INSTANCE = new NoopTraceConfig();

    @Override
    public boolean isTraceEnabled() {
      return false;
    }

    @Override
    public boolean isRuntimeMetricsEnabled() {
      return false;
    }

    @Override
    public boolean isLogsInjectionEnabled() {
      return false;
    }

    @Override
    public boolean isDataStreamsEnabled() {
      return false;
    }

    @Override
    public Map<String, String> getServiceMapping() {
      return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getRequestHeaderTags() {
      return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getResponseHeaderTags() {
      return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getBaggageMapping() {
      return Collections.emptyMap();
    }

    @Override
    public Double getTraceSampleRate() {
      return null;
    }

    @Override
    public Map<String, String> getTracingTags() {
      return Collections.emptyMap();
    }

    @Override
    public Pair<String, CharSequence> getPreferredServiceNameAndSource() {
      return null;
    }

    @Override
    public List<? extends SamplingRule.SpanSamplingRule> getSpanSamplingRules() {
      return Collections.emptyList();
    }

    @Override
    public List<? extends SamplingRule.TraceSamplingRule> getTraceSamplingRules() {
      return Collections.emptyList();
    }

    @Override
    public List<DataStreamsTransactionExtractor> getDataStreamsTransactionExtractors() {
      return null;
    }
  }

  /**
   * Decides whether to install the legacy approach to managing contexts, which requires a tracer.
   *
   * <p>Must be called ahead of instrumentation, before any use of the Context API.
   */
  public static void maybeInstallLegacyContextManager() {
    installLegacyContextManager(); // install everywhere to begin with
  }

  /**
   * Always install the legacy approach to managing contexts, which requires a tracer.
   *
   * <p>Must be called ahead of instrumentation, before any use of the Context API.
   */
  public static void installLegacyContextManager() {
    ContextManager.register(LegacyContextManager.INSTANCE);
  }

  /** Shim mapping new Context API to legacy scope manager. */
  static final class LegacyContextManager implements ContextManager {
    static final LegacyContextManager INSTANCE = new LegacyContextManager();

    @Override
    public Context current() {
      return AgentTracer.get().currentContext();
    }

    @Override
    public ContextScope attach(@Nonnull Context context) {
      return AgentTracer.get().attach(context);
    }

    @Override
    public ContextHandle swap(@Nonnull ContextHandle context) {
      return AgentTracer.get().swap(context);
    }

    @Override
    public ContextHandle checkpoint(@Nonnull Context context) {
      // Legacy flags stay on the scope stack, which subsequent swaps retain and restore.
      // Returning raw context is sufficient for coroutine/fiber callers during migration.
      return context;
    }

    @Override
    public ContextContinuation capture(@Nonnull Context context) {
      return AgentTracer.get().capture(context);
    }

    @Override
    public boolean isCaptureEnabled() {
      return AgentTracer.get().isCaptureEnabled();
    }

    @Override
    public void setCaptureEnabled(boolean enabled) {
      AgentTracer.get().setCaptureEnabled(enabled);
    }

    @Override
    public void addListener(@Nonnull ContextListener listener) {
      // this method is never used in legacy mode...
    }
  }

  /** Adapts a {@link ContextContinuation} to the deprecated {@link TraceScope.Continuation} SPI. */
  private static final class TraceScopeContinuationWrapper implements TraceScope.Continuation {
    private final ContextContinuation continuation;

    TraceScopeContinuationWrapper(ContextContinuation continuation) {
      this.continuation = continuation;
    }

    @Override
    public TraceScope.Continuation hold() {
      continuation.hold();
      return this;
    }

    @Override
    @SuppressWarnings("resource")
    public TraceScope activate() {
      ContextScope scope = continuation.resume();
      return scope instanceof TraceScope ? (TraceScope) scope : scope::close;
    }

    @Override
    public void cancel() {
      continuation.release();
    }
  }
}
