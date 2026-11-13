package datadog.trace.instrumentation.camel;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.camel.CamelDecorator.DECORATE;
import static datadog.trace.instrumentation.camel.CamelDecorator.ROUTE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Route;
import org.apache.camel.support.SynchronizationAdapter;

/** A copied exchange shares only immutable parent context, never a mutable route stack. */
public final class CamelState extends SynchronizationAdapter {
  private static final String KEY = CamelState.class.getName();
  private final CamelState previous;
  private final String routeId;
  private final String exchangeId;
  private final AgentSpan span;
  private final Context context;
  private final AtomicBoolean finished = new AtomicBoolean();

  private CamelState(
      CamelState previous, Route route, Exchange exchange, AgentSpan span, Context parent) {
    this.previous = previous;
    this.routeId = route.getId();
    this.exchangeId = exchange.getExchangeId();
    this.span = span;
    this.context = parent.with(span);
  }

  private static CamelState state(Exchange exchange) {
    return exchange.getProperty(KEY, CamelState.class);
  }

  public static Context context(Exchange exchange) {
    try {
      CamelState state = state(exchange);
      return state == null ? Context.current() : state.context;
    } catch (Throwable ignored) {
      return Context.current();
    }
  }

  public static void begin(Route route, Exchange exchange) {
    AgentSpan span = null;
    try {
      CamelState previous = state(exchange);
      Context parent = previous == null ? Context.current() : previous.context;
      AgentSpan active = AgentSpan.current();
      if (previous != null
          && active != null
          && active.getTraceId().equals(previous.span.getTraceId())) {
        parent = Context.current();
      }
      if (AgentSpan.fromContext(parent) == null) {
        parent =
            defaultPropagator()
                .extract(Context.root(), exchange.getIn().getHeaders(), Headers.INSTANCE);
      }
      try (ContextScope ignored = parent.attach()) {
        span = startSpan("camel", ROUTE);
      }
      DECORATE.afterStart(span);
      DECORATE.onRoute(span, route);
      CamelState state = new CamelState(previous, route, exchange, span, parent);
      exchange.getExchangeExtension().addOnCompletion(state);
      exchange.setProperty(KEY, state);
    } catch (Throwable ignored) {
      if (span != null) {
        span.finish();
      }
    }
  }

  public static void complete(Route route, Exchange exchange) {
    try {
      CamelState state = state(exchange);
      if (state != null
          && state.routeId.equals(route.getId())
          && state.exchangeId.equals(exchange.getExchangeId())) {
        state.onDone(exchange);
      }
    } catch (Throwable ignored) {
      // Instrumentation must not change route completion.
    }
  }

  @Override
  public void onDone(Exchange exchange) {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    try {
      // An older completion must not pop a newer route state from this exchange.
      if (state(exchange) == this) {
        if (previous == null) {
          exchange.removeProperty(KEY);
        } else {
          exchange.setProperty(KEY, previous);
        }
      }
      DECORATE.onError(span, exchange.getException());
      DECORATE.beforeFinish(span);
    } catch (Throwable ignored) {
      // The span still needs finishing if decoration or cleanup fails.
    } finally {
      span.finish();
    }
  }

  @Override
  public boolean allowHandover() {
    return false;
  }

  public static void propagate(Exchange exchange) {
    try {
      Context context = Context.current();
      if (AgentSpan.fromContext(context) == null) {
        context = context(exchange);
      }
      if (AgentSpan.fromContext(context) != null) {
        Message message = exchange.getIn();
        Map<String, Object> headers = new HashMap<>(message.getHeaders());
        defaultPropagator().inject(context, headers, Headers.INSTANCE);
        message.setHeaders(headers);
      }
    } catch (Throwable ignored) {
      // Preserve sends when headers or propagation are unavailable.
    }
  }

  public enum Headers
      implements CarrierSetter<Map<String, Object>>, CarrierVisitor<Map<String, Object>> {
    INSTANCE;

    @Override
    public void set(Map<String, Object> carrier, String key, String value) {
      carrier.put(key, value);
    }

    @Override
    public void forEachKeyValue(Map<String, Object> carrier, BiConsumer<String, String> visitor) {
      for (Map.Entry<String, Object> entry : carrier.entrySet()) {
        if (entry.getValue() instanceof CharSequence) {
          visitor.accept(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().toString());
        }
      }
    }
  }
}
