package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_ASYNC_PROPAGATING;

import datadog.trace.api.Checkpointer;
import datadog.trace.api.DDId;
import datadog.trace.api.SpanCheckpointer;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.context.ScopeListener;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.Map;

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

  public static TraceScope.Continuation captureSpan(final AgentSpan span) {
    return get().captureSpan(span, ScopeSource.INSTRUMENTATION);
  }

  public static AgentSpan activeSpan() {
    return get().activeSpan();
  }

  public static TraceScope activeScope() {
    return get().activeScope();
  }

  public static AgentPropagation propagate() {
    return get().propagate();
  }

  public static AgentSpan noopSpan() {
    return get().noopSpan();
  }

  private static final TracerAPI DEFAULT = new NoopTracerAPI();

  private static volatile TracerAPI provider = DEFAULT;

  public static boolean isRegistered() {
    return provider != DEFAULT;
  }

  public static synchronized void registerIfAbsent(final TracerAPI tracer) {
    if (tracer != null && tracer != DEFAULT) {
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

  public interface TracerAPI extends datadog.trace.api.Tracer, AgentPropagation, SpanCheckpointer {
    AgentSpan startSpan(CharSequence spanName);

    AgentSpan startSpan(CharSequence spanName, long startTimeMicros);

    AgentSpan startSpan(CharSequence spanName, AgentSpan.Context parent);

    AgentSpan startSpan(CharSequence spanName, AgentSpan.Context parent, long startTimeMicros);

    AgentScope activateSpan(AgentSpan span, ScopeSource source);

    AgentScope activateSpan(AgentSpan span, ScopeSource source, boolean isAsyncPropagating);

    TraceScope.Continuation captureSpan(AgentSpan span, ScopeSource source);

    AgentSpan activeSpan();

    TraceScope activeScope();

    AgentPropagation propagate();

    AgentSpan noopSpan();

    SpanBuilder buildSpan(CharSequence spanName);

    void close();

    void flush();

    /**
     * Registers the checkpointer
     *
     * @param checkpointer
     */
    void registerCheckpointer(Checkpointer checkpointer);

    InstrumentationGateway instrumentationGateway();
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
    public TraceScope.Continuation captureSpan(final AgentSpan span, final ScopeSource source) {
      return NoopContinuation.INSTANCE;
    }

    @Override
    public AgentSpan activeSpan() {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public TraceScope activeScope() {
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
    public void flush() {}

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
    public void registerCheckpointer(Checkpointer checkpointer) {}

    @Override
    public TraceScope.Continuation capture() {
      return null;
    }

    @Override
    public <C> void inject(final AgentSpan span, final C carrier, final Setter<C> setter) {}

    @Override
    public <C> void inject(final Context context, final C carrier, final Setter<C> setter) {}

    @Override
    public <C> Context.Extracted extract(final C carrier, final ContextVisitor<C> getter) {
      return null;
    }

    @Override
    public void checkpoint(AgentSpan span, int flags) {}

    @Override
    public void onStart(AgentSpan span) {}

    @Override
    public void onStartWork(AgentSpan span) {}

    @Override
    public void onFinishWork(AgentSpan span) {}

    @Override
    public void onStartThreadMigration(AgentSpan span) {}

    @Override
    public void onFinishThreadMigration(AgentSpan span) {}

    @Override
    public void onFinish(AgentSpan span) {}

    @Override
    public void onRootSpan(AgentSpan root, boolean published) {}

    @Override
    public InstrumentationGateway instrumentationGateway() {
      return null;
    }
  }

  public static class NoopAgentSpan implements AgentSpan {
    public static final NoopAgentSpan INSTANCE = new NoopAgentSpan();

    @Override
    public DDId getTraceId() {
      return DDId.ZERO;
    }

    @Override
    public DDId getSpanId() {
      return DDId.ZERO;
    }

    @Override
    public AgentSpan setTag(final String key, final boolean value) {
      return this;
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
    public boolean eligibleForDropping() {
      return true;
    }

    @Override
    public void startThreadMigration() {}

    @Override
    public void finishThreadMigration() {}

    @Override
    public void finishWork() {}

    @Override
    public RequestContext getRequestContext() {
      return null;
    }

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
      return otherSpan instanceof NoopAgentSpan;
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
    public void setEmittingCheckpoints(boolean value) {}

    @Override
    public Boolean isEmittingCheckpoints() {
      return Boolean.FALSE;
    }
  }

  public static class NoopAgentScope implements AgentScope, TraceScope {
    public static final NoopAgentScope INSTANCE = new NoopAgentScope();

    @Override
    public AgentSpan span() {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public void setAsyncPropagation(final boolean value) {}

    @Override
    public boolean checkpointed() {
      return false;
    }

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
    public <C> Context.Extracted extract(final C carrier, final ContextVisitor<C> getter) {
      return NoopContext.INSTANCE;
    }
  }

  static class NoopContinuation implements AgentScope.Continuation {
    static final NoopContinuation INSTANCE = new NoopContinuation();

    @Override
    public TraceScope activate() {
      return NoopAgentScope.INSTANCE;
    }

    @Override
    public void cancel() {}

    @Override
    public void migrate() {}
  }

  public static class NoopContext implements Context.Extracted {
    public static final NoopContext INSTANCE = new NoopContext();

    @Override
    public DDId getTraceId() {
      return DDId.ZERO;
    }

    @Override
    public DDId getSpanId() {
      return DDId.ZERO;
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
    public String getForwarded() {
      return null;
    }

    @Override
    public String getForwardedProto() {
      return null;
    }

    @Override
    public String getForwardedHost() {
      return null;
    }

    @Override
    public String getForwardedIp() {
      return null;
    }

    @Override
    public String getForwardedPort() {
      return null;
    }

    @Override
    public RequestContext getRequestContext() {
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
}
