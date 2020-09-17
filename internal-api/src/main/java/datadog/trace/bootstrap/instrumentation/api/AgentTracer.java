package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDId;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.context.ScopeListener;
import datadog.trace.context.TraceScope;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
    return get().activateSpan(span, ScopeSource.INSTRUMENTATION);
  }

  public static AgentSpan activeSpan() {
    return get().activeSpan();
  }

  // TODO figure out an alternative for this since it limits cleanup in ScopeManager.
  @Deprecated
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

  private static final AtomicReference<TracerAPI> provider = new AtomicReference<>(DEFAULT);

  public static boolean isRegistered() {
    return provider.get() != DEFAULT;
  }

  public static void registerIfAbsent(final TracerAPI trace) {
    if (trace != null && trace != DEFAULT) {
      provider.compareAndSet(DEFAULT, trace);
    }
  }

  public static TracerAPI get() {
    return provider.get();
  }

  // Not intended to be constructed.
  private AgentTracer() {}

  public interface TracerAPI extends datadog.trace.api.Tracer, AgentPropagation {
    AgentSpan startSpan(CharSequence spanName);

    AgentSpan startSpan(CharSequence spanName, long startTimeMicros);

    AgentSpan startSpan(CharSequence spanName, AgentSpan.Context parent);

    AgentSpan startSpan(CharSequence spanName, AgentSpan.Context parent, long startTimeMicros);

    AgentScope activateSpan(AgentSpan span, ScopeSource source);

    AgentSpan activeSpan();

    TraceScope activeScope();

    AgentPropagation propagate();

    AgentSpan noopSpan();

    SpanBuilder buildSpan(CharSequence spanName);

    void close();
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

    SpanBuilder withSpanType(String spanType);
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
    public TraceScope.Continuation capture() {
      return null;
    }

    @Override
    public <C> void inject(final AgentSpan span, final C carrier, final Setter<C> setter) {}

    @Override
    public <C> void inject(final Context context, final C carrier, final Setter<C> setter) {}

    @Override
    public <C> Context extract(final C carrier, final ContextVisitor<C> getter) {
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
    public AgentSpan setTag(final String key, final boolean value) {
      return this;
    }

    @Override
    public MutableSpan setTag(final String tag, final Number value) {
      return this;
    }

    @Override
    public Boolean isError() {
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
    public MutableSpan setOperationName(final CharSequence serviceName) {
      return this;
    }

    @Override
    public String getServiceName() {
      return null;
    }

    @Override
    public MutableSpan setServiceName(final String serviceName) {
      return this;
    }

    @Override
    public CharSequence getResourceName() {
      return null;
    }

    @Override
    public MutableSpan setResourceName(final CharSequence resourceName) {
      return this;
    }

    @Override
    public Integer getSamplingPriority() {
      return PrioritySampling.UNSET;
    }

    @Override
    public MutableSpan setSamplingPriority(final int newPriority) {
      return this;
    }

    @Override
    public String getSpanType() {
      return null;
    }

    @Override
    public MutableSpan setSpanType(final String type) {
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
    public AgentSpan setError(final boolean error) {
      return this;
    }

    @Override
    public MutableSpan getRootSpan() {
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
    public String getSpanName() {
      return "";
    }

    @Override
    public void setSpanName(final CharSequence spanName) {}

    @Override
    public boolean hasResourceName() {
      return false;
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
    public AgentScope.Continuation capture() {
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
    public <C> Context extract(final C carrier, final ContextVisitor<C> getter) {
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
    public boolean isRegistered() {
      return false;
    }

    @Override
    public WeakReference<AgentScope.Continuation> register(final ReferenceQueue referenceQueue) {
      return new WeakReference<>(null);
    }

    @Override
    public void cancel(final Set<WeakReference<AgentScope.Continuation>> weakReferences) {}
  }

  public static class NoopContext implements Context {
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
  }

  public static class NoopAgentTrace implements AgentTrace {
    public static final NoopAgentTrace INSTANCE = new NoopAgentTrace();

    @Override
    public void registerContinuation(final AgentScope.Continuation continuation) {}

    @Override
    public void cancelContinuation(final AgentScope.Continuation continuation) {}
  }
}
