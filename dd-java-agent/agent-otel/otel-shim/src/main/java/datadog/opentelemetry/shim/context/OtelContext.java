package datadog.opentelemetry.shim.context;

import datadog.opentelemetry.shim.trace.OtelSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import java.util.Arrays;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelContext implements Context {
  private static final Object[] NO_ENTRIES = {};

  /** Overridden root context. */
  public static final Context ROOT = new OtelContext(OtelSpan.invalid(), OtelSpan.invalid());

  private static final String OTEL_CONTEXT_SPAN_KEY = "opentelemetry-trace-span-key";
  private static final String OTEL_CONTEXT_ROOT_SPAN_KEY = "opentelemetry-traces-local-root-span";

  /** Keep track of propagated context that has not been captured on the scope stack. */
  private static final ThreadLocal<OtelContext> lastPropagated = new ThreadLocal<>();

  private final Span currentSpan;
  private final Span rootSpan;

  private final Object[] entries;

  public OtelContext(Span currentSpan, Span rootSpan) {
    this(currentSpan, rootSpan, NO_ENTRIES);
  }

  public OtelContext(Span currentSpan, Span rootSpan, Object[] entries) {
    this.currentSpan = currentSpan;
    this.rootSpan = rootSpan;
    this.entries = entries;
  }

  @Nullable
  @Override
  @SuppressWarnings("unchecked")
  public <V> V get(ContextKey<V> key) {
    if (OTEL_CONTEXT_SPAN_KEY.equals(key.toString())) {
      return (V) this.currentSpan;
    } else if (OTEL_CONTEXT_ROOT_SPAN_KEY.equals(key.toString())) {
      return (V) this.rootSpan;
    }
    for (int i = 0; i < this.entries.length; i += 2) {
      if (this.entries[i] == key) {
        return (V) this.entries[i + 1];
      }
    }
    return null;
  }

  @Override
  public <V> Context with(ContextKey<V> key, V value) {
    if (OTEL_CONTEXT_SPAN_KEY.equals(key.toString())) {
      return new OtelContext((Span) value, this.rootSpan, this.entries);
    } else if (OTEL_CONTEXT_ROOT_SPAN_KEY.equals(key.toString())) {
      return new OtelContext(this.currentSpan, (Span) value, this.entries);
    }
    Object[] newEntries = null;
    int oldEntriesLength = this.entries.length;
    for (int i = 0; i < oldEntriesLength; i += 2) {
      if (this.entries[i] == key) {
        if (this.entries[i + 1] == value) {
          return this;
        }
        newEntries = this.entries.clone();
        newEntries[i + 1] = value;
        break;
      }
    }
    if (null == newEntries) {
      newEntries = Arrays.copyOf(this.entries, oldEntriesLength + 2);
      newEntries[oldEntriesLength] = key;
      newEntries[oldEntriesLength + 1] = value;
    }
    return new OtelContext(this.currentSpan, this.rootSpan, newEntries);
  }

  @Override
  public Scope makeCurrent() {
    final Scope scope = Context.super.makeCurrent();
    if (this.currentSpan instanceof OtelSpan) {
      // only keep propagated context until next span activation
      lastPropagated.remove();
      AgentScope agentScope = ((OtelSpan) this.currentSpan).activate();
      return new OtelScope(scope, agentScope, this.entries);
    } else {
      // propagated context not on the scope stack, capture it here
      lastPropagated.set(this);
      return new Scope() {
        @Override
        public void close() {
          lastPropagated.remove();
          scope.close();
        }
      };
    }
  }

  public static Context current() {
    // Check for propagated context not on the scope stack
    Context context = lastPropagated.get();
    if (null != context) {
      return context;
    }
    // Check empty context
    AgentScope agentCurrentScope = AgentTracer.activeScope();
    if (null == agentCurrentScope) {
      return OtelContext.ROOT;
    }
    // Get OTel current span
    Span otelCurrentSpan = null;
    AgentSpan agentCurrentSpan = agentCurrentScope.span();
    if (agentCurrentSpan instanceof AttachableWrapper) {
      Object wrapper = ((AttachableWrapper) agentCurrentSpan).getWrapper();
      if (wrapper instanceof OtelSpan) {
        otelCurrentSpan = (OtelSpan) wrapper;
      }
    }
    if (otelCurrentSpan == null) {
      otelCurrentSpan = new OtelSpan(agentCurrentSpan);
    }
    // Get OTel root span
    Span otelRootSpan = null;
    AgentSpan agentRootSpan = agentCurrentSpan.getLocalRootSpan();
    if (agentRootSpan instanceof AttachableWrapper) {
      Object wrapper = ((AttachableWrapper) agentRootSpan).getWrapper();
      if (wrapper instanceof OtelSpan) {
        otelRootSpan = (OtelSpan) wrapper;
      }
    }
    if (otelRootSpan == null) {
      otelRootSpan = new OtelSpan(agentRootSpan);
    }
    // Get OTel custom context entries
    Object[] contextEntries = NO_ENTRIES;
    if (agentCurrentScope instanceof AttachableWrapper) {
      Object wrapper = ((AttachableWrapper) agentCurrentScope).getWrapper();
      if (wrapper instanceof OtelScope) {
        contextEntries = ((OtelScope) wrapper).contextEntries();
      }
    }
    return new OtelContext(otelCurrentSpan, otelRootSpan, contextEntries);
  }

  /** Last propagated context not on the scope stack; {@code null} if there's no such context. */
  @Nullable
  public static Context lastPropagated() {
    return lastPropagated.get();
  }

  @Override
  public String toString() {
    return "OtelContext{"
        + "currentSpan="
        + this.currentSpan.getSpanContext()
        + ", rootSpan="
        + this.rootSpan.getSpanContext()
        + '}';
  }
}
