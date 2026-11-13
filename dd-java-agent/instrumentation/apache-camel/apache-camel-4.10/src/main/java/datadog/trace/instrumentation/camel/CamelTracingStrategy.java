package datadog.trace.instrumentation.camel;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spi.RoutePolicyFactory;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.camel.support.RoutePolicySupport;

/** Installs the same public lifecycle seams used by Camel's own tracing extension. */
public final class CamelTracingStrategy extends EventNotifierSupport
    implements RoutePolicyFactory, InterceptStrategy {
  private final CamelContext context;
  private final Class<?> nativeTracer;

  private CamelTracingStrategy(CamelContext context) {
    this.context = context;
    Class<?> tracer;
    try {
      tracer =
          Class.forName(
              "org.apache.camel.tracing.Tracer", false, context.getClass().getClassLoader());
    } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
      tracer = null;
    }
    nativeTracer = tracer;
    setupIgnore(true);
    setIgnoreExchangeEvents(false);
    setIgnoreExchangeSendingEvents(false);
    setIgnoreCamelContextEvents(true);
    setIgnoreCamelContextInitEvents(true);
    setIgnoreRouteEvents(true);
    setIgnoreServiceEvents(true);
  }

  public static void install(CamelContext context) {
    for (RoutePolicyFactory factory : context.getRoutePolicyFactories()) {
      if (factory instanceof CamelTracingStrategy) {
        return;
      }
    }
    CamelTracingStrategy strategy = new CamelTracingStrategy(context);
    context.addRoutePolicyFactory(strategy);
    context.getCamelContextExtension().addInterceptStrategy(strategy);
    context.getManagementStrategy().addEventNotifier(strategy);
  }

  private boolean nativeTracingPresent() {
    // Camel's tracing adapters register their Tracer as a context service before routing.
    try {
      return nativeTracer != null && context.hasService(nativeTracer) != null;
    } catch (Throwable ignored) {
      return true;
    }
  }

  @Override
  public RoutePolicy createRoutePolicy(CamelContext context, String routeId, NamedNode route) {
    try {
      return new Policy(this);
    } catch (Throwable ignored) {
      return null;
    }
  }

  @Override
  public Processor wrapProcessorInInterceptors(
      CamelContext context, NamedNode definition, Processor target, Processor nextTarget) {
    try {
      return target instanceof CamelTracingProcessor ? target : new CamelTracingProcessor(target);
    } catch (Throwable ignored) {
      return target;
    }
  }

  @Override
  public boolean isEnabled(CamelEvent event) {
    return event instanceof CamelEvent.ExchangeSendingEvent && !nativeTracingPresent();
  }

  @Override
  public void notify(CamelEvent event) {
    if (event instanceof CamelEvent.ExchangeSendingEvent) {
      CamelState.propagate(((CamelEvent.ExchangeSendingEvent) event).getExchange());
    }
  }

  public static final class Policy extends RoutePolicySupport {
    private final CamelTracingStrategy strategy;

    public Policy(CamelTracingStrategy strategy) {
      this.strategy = strategy;
    }

    @Override
    public void onExchangeBegin(Route route, Exchange exchange) {
      try {
        if (!strategy.nativeTracingPresent()) {
          CamelState.begin(route, exchange);
        }
      } catch (Throwable ignored) {
        // Optional tracing services must not affect routing.
      }
    }

    @Override
    public void onExchangeDone(Route route, Exchange exchange) {
      CamelState.complete(route, exchange);
    }
  }
}
