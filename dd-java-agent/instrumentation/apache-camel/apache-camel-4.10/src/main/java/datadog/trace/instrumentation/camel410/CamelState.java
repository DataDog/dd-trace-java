package datadog.trace.instrumentation.camel410;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.camel410.CamelDecorator.DECORATE;
import static datadog.trace.instrumentation.camel410.CamelDecorator.ROUTE;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Route;
import org.apache.camel.SafeCopyProperty;
import org.apache.camel.spi.UnitOfWork;
import org.apache.camel.support.SynchronizationAdapter;
import org.apache.camel.util.CaseInsensitiveMap;

/** A copied exchange shares only immutable parent context, never a mutable route stack. */
public final class CamelState extends SynchronizationAdapter implements SafeCopyProperty {
  private static final String KEY = CamelState.class.getName();
  private final SafeCopyProperty previous;
  private final String routeId;
  private final AgentSpan span;
  private final Context context;
  private final AtomicBoolean finished = new AtomicBoolean();

  private CamelState(SafeCopyProperty previous, Route route, AgentSpan span, Context parent) {
    this.previous = previous;
    this.routeId = route.getId();
    this.span = span;
    this.context = parent.with(span);
  }

  private static SafeCopyProperty state(Exchange exchange) {
    return exchange.getExchangeExtension().getSafeCopyProperty(KEY, SafeCopyProperty.class);
  }

  private static Context context(SafeCopyProperty state) {
    if (state instanceof CamelState) {
      return ((CamelState) state).context;
    }
    return state == null ? null : ((ParentContext) state).context;
  }

  public static Context exchangeContext(Exchange exchange) {
    try {
      return context(state(exchange));
    } catch (Throwable ignored) {
      return null;
    }
  }

  public static void begin(Route route, Exchange exchange) {
    AgentSpan span = null;
    try {
      SafeCopyProperty previous = state(exchange);
      Context inherited = context(previous);
      Context parent = inherited == null ? Context.current() : inherited;
      AgentSpan inheritedSpan = inherited == null ? null : AgentSpan.fromContext(inherited);
      AgentSpan active = AgentSpan.current();
      if (inheritedSpan != null
          && active != null
          && active.getTraceId().equals(inheritedSpan.getTraceId())) {
        parent = Context.current();
      }
      if (AgentSpan.fromContext(parent) == null) {
        parent =
            defaultPropagator().extract(parent, exchange.getIn().getHeaders(), Headers.INSTANCE);
      }
      AgentSpan parentSpan = AgentSpan.fromContext(parent);
      span = startSpan("camel", ROUTE, parentSpan == null ? null : parentSpan.spanContext());
      DECORATE.afterStart(span);
      DECORATE.onRoute(span, route);
      CamelState state = new CamelState(previous, route, span, parent);
      exchange.getExchangeExtension().addOnCompletion(state);
      exchange.getExchangeExtension().setSafeCopyProperty(KEY, state);
    } catch (Throwable ignored) {
      if (span != null) {
        span.finish();
      }
    }
  }

  public static void complete(Route route, Exchange exchange) {
    try {
      SafeCopyProperty current = state(exchange);
      if (current instanceof CamelState && ((CamelState) current).routeId.equals(route.getId())) {
        CamelState state = (CamelState) current;
        try {
          UnitOfWork unitOfWork = exchange.getUnitOfWork();
          if (unitOfWork != null) {
            unitOfWork.removeSynchronization(state);
          }
        } finally {
          state.onDone(exchange);
        }
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
        exchange.getExchangeExtension().setSafeCopyProperty(KEY, previous);
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

  @Override
  public SafeCopyProperty safeCopy() {
    return new ParentContext(context);
  }

  public static final class ParentContext implements SafeCopyProperty {
    private final Context context;

    private ParentContext(Context context) {
      this.context = context;
    }

    @Override
    public SafeCopyProperty safeCopy() {
      return this;
    }
  }

  public static void propagate(Exchange exchange) {
    try {
      Context context = Context.current();
      if (AgentSpan.fromContext(context) == null) {
        context = exchangeContext(exchange);
      }
      if (context != null && AgentSpan.fromContext(context) != null) {
        Message message = exchange.getIn();
        Map<String, Object> headers = message.getHeaders();
        if (headers.getClass() == CaseInsensitiveMap.class) {
          defaultPropagator().inject(context, headers, Headers.INSTANCE);
        } else {
          // Custom message maps may be immutable or shared with another exchange.
          headers = new HashMap<>(headers);
          defaultPropagator().inject(context, headers, Headers.INSTANCE);
          message.setHeaders(headers);
        }
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
      if (carrier.getClass() != CaseInsensitiveMap.class) {
        Iterator<String> keys = carrier.keySet().iterator();
        while (keys.hasNext()) {
          if (key.equalsIgnoreCase(keys.next())) {
            keys.remove();
          }
        }
      }
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
