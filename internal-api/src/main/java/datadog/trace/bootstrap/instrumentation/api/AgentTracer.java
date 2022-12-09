package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_ASYNC_PROPAGATING;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.PropagationStyle;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.internal.InternalTracer;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class AgentTracer {

  // Implicit parent
  public static AgentSpan startSpan(final CharSequence spanName) {
    return get().startSpan(spanName);
  }

  // Implicit parent
  public static AgentSpan startSpan(final CharSequence spanName, final long startTimeMicros) {
    return get().startSpan(spanName, startTimeMicros);
  }

  // Explicit parent
  public static AgentSpan startSpan(final CharSequence spanName, final AgentSpan.Context parent) {
    return get().startSpan(spanName, parent);
  }

  // Explicit parent
  public static AgentSpan startSpan(
      final CharSequence spanName, final AgentSpan.Context parent, final long startTimeMicros) {
    return get().startSpan(spanName, parent, startTimeMicros);
  }

  public static AgentScope activateSpan(final AgentSpan span) {
    return get().activateSpan(span, ScopeSource.INSTRUMENTATION, DEFAULT_ASYNC_PROPAGATING);
  }

  public static AgentScope activateSpan(final AgentSpan span, final boolean isAsyncPropagating) {
    return get().activateSpan(span, ScopeSource.INSTRUMENTATION, isAsyncPropagating);
  }

  public static AgentScope.Continuation captureSpan(final AgentSpan span) {
    return get().captureSpan(span, ScopeSource.INSTRUMENTATION);
  }

  /**
   * Closes the immediately previous iteration scope. Should be called before creating a new span
   * for {@link #activateNext(AgentSpan)}.
   */
  public static void closePrevious(final boolean finishSpan) {
    get().closePrevious(finishSpan);
  }

  /**
   * Activates a new iteration scope; closes automatically after a fixed period.
   *
   * @see datadog.trace.api.config.TracerConfig#SCOPE_ITERATION_KEEP_ALIVE
   */
  public static AgentScope activateNext(final AgentSpan span) {
    return get().activateNext(span);
  }

  public static AgentSpan activeSpan() {
    return get().activeSpan();
  }

  public static AgentScope activeScope() {
    return get().activeScope();
  }

  public static AgentPropagation propagate() {
    return get().propagate();
  }

  public static AgentSpan noopSpan() {
    return get().noopSpan();
  }

  public static final TracerAPI NOOP_TRACER = new NoopTracerAPI();

  private static volatile TracerAPI provider = NOOP_TRACER;

  public static boolean isRegistered() {
    return provider != NOOP_TRACER;
  }

  public static synchronized void registerIfAbsent(final TracerAPI tracer) {
    if (tracer != null && tracer != NOOP_TRACER) {
      provider = tracer;
    }
  }

  public static synchronized void forceRegister(TracerAPI tracer) {
    provider = tracer;
  }

  public static TracerAPI get() {
    return provider;
  }

  // Not intended to be constructed.
  private AgentTracer() {}

  public interface TracerAPI
      extends datadog.trace.api.Tracer,
          InternalTracer,
          AgentPropagation,
          EndpointCheckpointer,
          ScopeStateAware {
    AgentSpan startSpan(CharSequence spanName);

    AgentSpan startSpan(CharSequence spanName, long startTimeMicros);

    AgentSpan startSpan(CharSequence spanName, AgentSpan.Context parent);

    AgentSpan startSpan(CharSequence spanName, AgentSpan.Context parent, long startTimeMicros);

    AgentScope activateSpan(AgentSpan span, ScopeSource source);

    AgentScope activateSpan(AgentSpan span, ScopeSource source, boolean isAsyncPropagating);

    AgentScope.Continuation captureSpan(AgentSpan span, ScopeSource source);

    void closePrevious(boolean finishSpan);

    AgentScope activateNext(AgentSpan span);

    AgentSpan activeSpan();

    AgentScope activeScope();

    AgentPropagation propagate();

    AgentSpan noopSpan();

    SpanBuilder buildSpan(CharSequence spanName);

    void close();

    /**
     * Attach a scope listener to the global scope manager
     *
     * @param listener listener to attach
     */
    void addScopeListener(ScopeListener listener);

    /**
     * Registers the checkpointer
     *
     * @param checkpointer
     */
    void registerCheckpointer(EndpointCheckpointer checkpointer);

    SubscriptionService getSubscriptionService(RequestContextSlot slot);

    CallbackProvider getCallbackProvider(RequestContextSlot slot);

    CallbackProvider getUniversalCallbackProvider();

    void setDataStreamCheckpoint(AgentSpan span, LinkedHashMap<String, String> sortedTags);

    AgentSpan.Context notifyExtensionStart(Object event);

    void notifyExtensionEnd(AgentSpan span, Object result, boolean isError);
  }

  public interface SpanBuilder {
    AgentSpan start();

    SpanBuilder asChildOf(Context toContext);

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
  }

  static class NoopTracerAPI implements TracerAPI {

    protected NoopTracerAPI() {}

    @Override
    public AgentSpan startSpan(final CharSequence spanName) {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentSpan startSpan(final CharSequence spanName, final long startTimeMicros) {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentSpan startSpan(final CharSequence spanName, final Context parent) {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentSpan startSpan(
        final CharSequence spanName, final Context parent, final long startTimeMicros) {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentScope activateSpan(final AgentSpan span, final ScopeSource source) {
      return NoopAgentScope.INSTANCE;
    }

    @Override
    public AgentScope activateSpan(
        final AgentSpan span, final ScopeSource source, final boolean isAsyncPropagating) {
      return NoopAgentScope.INSTANCE;
    }

    @Override
    public AgentScope.Continuation captureSpan(final AgentSpan span, final ScopeSource source) {
      return NoopContinuation.INSTANCE;
    }

    @Override
    public void closePrevious(final boolean finishSpan) {}

    @Override
    public AgentScope activateNext(final AgentSpan span) {
      return NoopAgentScope.INSTANCE;
    }

    @Override
    public AgentSpan activeSpan() {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentScope activeScope() {
      return null;
    }

    @Override
    public AgentPropagation propagate() {
      return NoopAgentPropagation.INSTANCE;
    }

    @Override
    public AgentSpan noopSpan() {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public SpanBuilder buildSpan(final CharSequence spanName) {
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
    public String getTraceId() {
      return null;
    }

    @Override
    public String getSpanId() {
      return null;
    }

    @Override
    public boolean addTraceInterceptor(final TraceInterceptor traceInterceptor) {
      return false;
    }

    @Override
    public void addScopeListener(final ScopeListener listener) {}

    @Override
    public void registerCheckpointer(EndpointCheckpointer checkpointer) {}

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
    public AgentScope.Continuation capture() {
      return null;
    }

    @Override
    public <C> void inject(final AgentSpan span, final C carrier, final Setter<C> setter) {}

    @Override
    public <C> void inject(final Context context, final C carrier, final Setter<C> setter) {}

    @Override
    public <C> void inject(AgentSpan span, C carrier, Setter<C> setter, PropagationStyle style) {}

    @Override
    public <C> void injectBinaryPathwayContext(
        AgentSpan span,
        C carrier,
        BinarySetter<C> setter,
        LinkedHashMap<String, String> sortedTags) {}

    @Override
    public <C> void injectPathwayContext(
        AgentSpan span, C carrier, Setter<C> setter, LinkedHashMap<String, String> sortedTags) {}

    @Override
    public <C> Context.Extracted extract(final C carrier, final ContextVisitor<C> getter) {
      return null;
    }

    @Override
    public <C> PathwayContext extractBinaryPathwayContext(
        C carrier, BinaryContextVisitor<C> getter) {
      return null;
    }

    @Override
    public <C> PathwayContext extractPathwayContext(C carrier, ContextVisitor<C> getter) {
      return null;
    }

    @Override
    public void onRootSpanFinished(AgentSpan root, boolean published) {}

    @Override
    public void onRootSpanStarted(AgentSpan root) {}

    @Override
    public void setDataStreamCheckpoint(AgentSpan span, LinkedHashMap<String, String> sortedTags) {}

    @Override
    public AgentSpan.Context notifyExtensionStart(Object event) {
      return null;
    }

    @Override
    public void notifyExtensionEnd(AgentSpan span, Object result, boolean isError) {}

    @Override
    public ScopeState newScopeState() {
      return null;
    }
  }

  public static final class NoopAgentSpan implements AgentSpan {
    public static final NoopAgentSpan INSTANCE = new NoopAgentSpan();

    private NoopAgentSpan() {}

    @Override
    public DDTraceId getTraceId() {
      return DDTraceId.ZERO;
    }

    @Override
    public long getSpanId() {
      return DDSpanId.ZERO;
    }

    @Override
    public AgentSpan setTag(final String key, final boolean value) {
      return this;
    }

    @Override
    public void setRequestBlockingAction(Flow.Action.RequestBlockingAction rba) {}

    @Override
    public Flow.Action.RequestBlockingAction getRequestBlockingAction() {
      return null;
    }

    @Override
    public AgentSpan setTag(final String tag, final Number value) {
      return this;
    }

    @Override
    public boolean isError() {
      return false;
    }

    @Override
    public AgentSpan setTag(final String key, final int value) {
      return this;
    }

    @Override
    public AgentSpan setTag(final String key, final long value) {
      return this;
    }

    @Override
    public AgentSpan setTag(final String key, final double value) {
      return this;
    }

    @Override
    public AgentSpan setTag(final String key, final Object value) {
      return this;
    }

    @Override
    public AgentSpan setMetric(final CharSequence key, final int value) {
      return this;
    }

    @Override
    public AgentSpan setMetric(final CharSequence key, final long value) {
      return this;
    }

    @Override
    public AgentSpan setMetric(final CharSequence key, final double value) {
      return this;
    }

    @Override
    public Object getTag(final String key) {
      return null;
    }

    @Override
    public long getStartTime() {
      return 0;
    }

    @Override
    public long getDurationNano() {
      return 0;
    }

    @Override
    public String getOperationName() {
      return null;
    }

    @Override
    public AgentSpan setOperationName(final CharSequence serviceName) {
      return this;
    }

    @Override
    public String getServiceName() {
      return null;
    }

    @Override
    public AgentSpan setServiceName(final String serviceName) {
      return this;
    }

    @Override
    public CharSequence getResourceName() {
      return null;
    }

    @Override
    public AgentSpan setResourceName(final CharSequence resourceName) {
      return this;
    }

    @Override
    public AgentSpan setResourceName(final CharSequence resourceName, byte priority) {
      return this;
    }

    @Override
    public boolean eligibleForDropping() {
      return true;
    }

    @Override
    public void startWork() {}

    @Override
    public void finishWork() {}

    @Override
    public RequestContext getRequestContext() {
      return null;
    }

    @Override
    public void mergePathwayContext(PathwayContext pathwayContext) {}

    @Override
    public Integer getSamplingPriority() {
      return (int) PrioritySampling.UNSET;
    }

    @Override
    public AgentSpan setSamplingPriority(final int newPriority) {
      return this;
    }

    @Override
    public String getSpanType() {
      return null;
    }

    @Override
    public AgentSpan setSpanType(final CharSequence type) {
      return this;
    }

    @Override
    public Map<String, Object> getTags() {
      return Collections.emptyMap();
    }

    @Override
    public AgentSpan setTag(final String key, final String value) {
      return this;
    }

    @Override
    public AgentSpan setTag(final String key, final CharSequence value) {
      return this;
    }

    @Override
    public AgentSpan setError(final boolean error) {
      return this;
    }

    @Override
    public AgentSpan setMeasured(boolean measured) {
      return this;
    }

    @Override
    public AgentSpan getRootSpan() {
      return this;
    }

    @Override
    public AgentSpan setErrorMessage(final String errorMessage) {
      return this;
    }

    @Override
    public AgentSpan addThrowable(final Throwable throwable) {
      return this;
    }

    @Override
    public AgentSpan setHttpStatusCode(int statusCode) {
      return this;
    }

    @Override
    public short getHttpStatusCode() {
      return 0;
    }

    @Override
    public AgentSpan getLocalRootSpan() {
      return this;
    }

    @Override
    public boolean isSameTrace(final AgentSpan otherSpan) {
      // FIXME [API] AgentSpan or AgentSpan.Context should have a "getTraceId()" type method
      // Not sure if this is the best idea...
      return otherSpan == INSTANCE;
    }

    @Override
    public Context context() {
      return NoopContext.INSTANCE;
    }

    @Override
    public String getBaggageItem(final String key) {
      return null;
    }

    @Override
    public AgentSpan setBaggageItem(final String key, final String value) {
      return this;
    }

    @Override
    public void finish() {}

    @Override
    public void finish(final long finishMicros) {}

    @Override
    public void finishWithDuration(final long durationNanos) {}

    @Override
    public void beginEndToEnd() {}

    @Override
    public void finishWithEndToEnd() {}

    @Override
    public boolean phasedFinish() {
      return false;
    }

    @Override
    public void publish() {}

    @Override
    public String getSpanName() {
      return "";
    }

    @Override
    public void setSpanName(final CharSequence spanName) {}

    @Override
    public boolean hasResourceName() {
      return false;
    }

    @Override
    public byte getResourceNamePriority() {
      return Byte.MAX_VALUE;
    }
  }

  public static final class NoopAgentScope implements AgentScope {
    public static final NoopAgentScope INSTANCE = new NoopAgentScope();

    private NoopAgentScope() {}

    @Override
    public AgentSpan span() {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public byte source() {
      return 0;
    }

    @Override
    public void setAsyncPropagation(final boolean value) {}

    @Override
    public AgentScope.Continuation capture() {
      return NoopContinuation.INSTANCE;
    }

    @Override
    public AgentScope.Continuation captureConcurrent() {
      return NoopContinuation.INSTANCE;
    }

    @Override
    public void close() {}

    @Override
    public boolean isAsyncPropagating() {
      return false;
    }
  }

  static class NoopAgentPropagation implements AgentPropagation {
    static final NoopAgentPropagation INSTANCE = new NoopAgentPropagation();

    @Override
    public AgentScope.Continuation capture() {
      return NoopContinuation.INSTANCE;
    }

    @Override
    public <C> void inject(final AgentSpan span, final C carrier, final Setter<C> setter) {}

    @Override
    public <C> void inject(final Context context, final C carrier, final Setter<C> setter) {}

    @Override
    public <C> void inject(AgentSpan span, C carrier, Setter<C> setter, PropagationStyle style) {}

    @Override
    public <C> void injectBinaryPathwayContext(
        AgentSpan span,
        C carrier,
        BinarySetter<C> setter,
        LinkedHashMap<String, String> sortedTags) {}

    @Override
    public <C> void injectPathwayContext(
        AgentSpan span, C carrier, Setter<C> setter, LinkedHashMap<String, String> sortedTags) {}

    @Override
    public <C> Context.Extracted extract(final C carrier, final ContextVisitor<C> getter) {
      return NoopContext.INSTANCE;
    }

    @Override
    public <C> PathwayContext extractBinaryPathwayContext(
        C carrier, BinaryContextVisitor<C> getter) {
      return null;
    }

    @Override
    public <C> PathwayContext extractPathwayContext(C carrier, ContextVisitor<C> getter) {
      return null;
    }
  }

  static class NoopContinuation implements AgentScope.Continuation {
    static final NoopContinuation INSTANCE = new NoopContinuation();

    @Override
    public AgentScope activate() {
      return NoopAgentScope.INSTANCE;
    }

    @Override
    public void cancel() {}

    @Override
    public AgentSpan getSpan() {
      return NoopAgentSpan.INSTANCE;
    }
  }

  public static final class NoopContext implements Context.Extracted {
    public static final NoopContext INSTANCE = new NoopContext();

    private NoopContext() {}

    @Override
    public DDTraceId getTraceId() {
      return DDTraceId.ZERO;
    }

    @Override
    public long getSpanId() {
      return DDSpanId.ZERO;
    }

    @Override
    public AgentTrace getTrace() {
      return NoopAgentTrace.INSTANCE;
    }

    @Override
    public Iterable<Map.Entry<String, String>> baggageItems() {
      return Collections.emptyList();
    }

    @Override
    public PathwayContext getPathwayContext() {
      return NoopPathwayContext.INSTANCE;
    }

    @Override
    public String getForwarded() {
      return null;
    }

    @Override
    public String getXForwardedProto() {
      return null;
    }

    @Override
    public String getXForwardedHost() {
      return null;
    }

    @Override
    public String getXForwardedPort() {
      return null;
    }

    @Override
    public String getForwardedFor() {
      return null;
    }

    @Override
    public String getXForwarded() {
      return null;
    }

    @Override
    public String getXForwardedFor() {
      return null;
    }

    @Override
    public String getXClusterClientIp() {
      return null;
    }

    @Override
    public String getXRealIp() {
      return null;
    }

    @Override
    public String getClientIp() {
      return null;
    }

    @Override
    public String getUserAgent() {
      return null;
    }

    @Override
    public String getVia() {
      return null;
    }

    @Override
    public String getTrueClientIp() {
      return null;
    }

    @Override
    public String getCustomIpHeader() {
      return null;
    }
  }

  public static class NoopAgentTrace implements AgentTrace {
    public static final NoopAgentTrace INSTANCE = new NoopAgentTrace();

    @Override
    public void registerContinuation(final AgentScope.Continuation continuation) {}

    @Override
    public void cancelContinuation(final AgentScope.Continuation continuation) {}
  }

  public static class NoopPathwayContext implements PathwayContext {
    public static final NoopPathwayContext INSTANCE = new NoopPathwayContext();

    @Override
    public boolean isStarted() {
      return false;
    }

    @Override
    public void setCheckpoint(
        LinkedHashMap<String, String> sortedTags, Consumer<StatsPoint> pointConsumer) {}

    @Override
    public byte[] encode() throws IOException {
      return null;
    }

    @Override
    public String strEncode() throws IOException {
      return null;
    }
  }
}
