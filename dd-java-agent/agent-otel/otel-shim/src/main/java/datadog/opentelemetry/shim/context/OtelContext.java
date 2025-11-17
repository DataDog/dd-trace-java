package datadog.opentelemetry.shim.context;

import datadog.opentelemetry.shim.baggage.OtelBaggage;
import datadog.opentelemetry.shim.trace.OtelSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@SuppressWarnings({"rawtypes", "unchecked"})
@ParametersAreNonnullByDefault
public class OtelContext implements Context {

  /** Overridden root context. */
  public static final Context ROOT = new OtelContext(datadog.context.Context.root());

  private static final String OTEL_CONTEXT_BAGGAGE_KEY = "opentelemetry-baggage-key";
  private static final String OTEL_CONTEXT_SPAN_KEY = "opentelemetry-trace-span-key";
  private static final String OTEL_CONTEXT_ROOT_SPAN_KEY = "opentelemetry-traces-local-root-span";

  /** Records the keys needed to access the delegate context, mapped by key name. */
  private static final Map<ContextKey<?>, datadog.context.ContextKey<?>> DELEGATE_KEYS =
      new ConcurrentHashMap<>();

  private final datadog.context.Context delegate;

  public OtelContext(datadog.context.Context delegate) {
    this.delegate = delegate;
  }

  public static Context current() {
    return new OtelContext(datadog.context.Context.current());
  }

  @Override
  public Scope makeCurrent() {
    return new OtelScope(Context.super.makeCurrent(), delegate.attach());
  }

  @Nullable
  @Override
  public <V> V get(ContextKey<V> key) {
    if (OTEL_CONTEXT_SPAN_KEY.equals(key.toString())) {
      AgentSpan span = AgentSpan.fromContext(delegate);
      if (span != null && span.isValid()) {
        return (V) toOtelSpan(span);
      }
      // fall-through and check for non-datadog span data
    } else if (OTEL_CONTEXT_ROOT_SPAN_KEY.equals(key.toString())) {
      AgentSpan span = AgentSpan.fromContext(delegate);
      if (span != null && span.isValid()) {
        return (V) toOtelSpan(span.getLocalRootSpan());
      }
      // fall-through and check for non-datadog span data
    } else if (OTEL_CONTEXT_BAGGAGE_KEY.equals(key.toString())) {
      Baggage baggage = Baggage.fromContext(delegate);
      if (baggage != null) {
        return (V) new OtelBaggage(baggage);
      }
      // fall-through and check for non-datadog baggage
    }
    return (V) delegate.get(delegateKey(key));
  }

  @Override
  public <V> Context with(ContextKey<V> key, V value) {
    if (OTEL_CONTEXT_SPAN_KEY.equals(key.toString())) {
      if (value instanceof OtelSpan) {
        AgentSpan span = ((OtelSpan) value).asAgentSpan();
        return new OtelContext(delegate.with(span));
      }
      // fall-through and store as non-datadog span data
    } else if (OTEL_CONTEXT_BAGGAGE_KEY.equals(key.toString())) {
      if (value instanceof OtelBaggage) {
        Baggage baggage = ((OtelBaggage) value).asAgentBaggage();
        return new OtelContext(delegate.with(baggage));
      }
      // fall-through and store as non-datadog baggage
    }
    return new OtelContext(delegate.with(delegateKey(key), value));
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    return delegate.equals(((OtelContext) o).delegate);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }

  @Override
  public String toString() {
    return "OtelContext{" + "delegate=" + delegate + '}';
  }

  /**
   * Returns the underlying context.
   *
   * @return The underlying context.
   */
  public datadog.context.Context asContext() {
    return this.delegate;
  }

  private static datadog.context.ContextKey delegateKey(ContextKey key) {
    return DELEGATE_KEYS.computeIfAbsent(key, OtelContext::mapByKeyName);
  }

  private static datadog.context.ContextKey mapByKeyName(ContextKey key) {
    return datadog.context.ContextKey.named(key.toString());
  }

  private static OtelSpan toOtelSpan(AgentSpan span) {
    if (span instanceof AttachableWrapper) {
      Object wrapper = ((AttachableWrapper) span).getWrapper();
      if (wrapper instanceof OtelSpan) {
        return (OtelSpan) wrapper;
      }
    }
    return new OtelSpan(span);
  }
}
