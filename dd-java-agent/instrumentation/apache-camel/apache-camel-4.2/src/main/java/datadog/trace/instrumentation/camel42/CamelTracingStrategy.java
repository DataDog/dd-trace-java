package datadog.trace.instrumentation.camel42;

import org.apache.camel.CamelContext;
import org.apache.camel.Component;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.CamelInternalProcessorAdvice;
import org.apache.camel.spi.CamelTracingService;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.spi.InternalProcessor;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spi.RoutePolicyFactory;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.camel.support.RoutePolicySupport;

/** Installs the same public lifecycle seams used by Camel's own tracing extension. */
public final class CamelTracingStrategy extends EventNotifierSupport
    implements RoutePolicyFactory, InterceptStrategy {
  private final CamelContext context;
  private volatile Boolean nativeTracingPresent;

  private CamelTracingStrategy(CamelContext context) {
    this.context = context;
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

  public static void invalidateNativeTracing(CamelContext context) {
    for (RoutePolicyFactory factory : context.getRoutePolicyFactories()) {
      if (factory instanceof CamelTracingStrategy) {
        ((CamelTracingStrategy) factory).clearNativeTracing();
      }
    }
  }

  private synchronized void clearNativeTracing() {
    nativeTracingPresent = null;
  }

  @Override
  protected void doStart() {
    clearNativeTracing();
  }

  private boolean nativeTracingPresent() {
    Boolean present = nativeTracingPresent;
    if (present != null) {
      return present;
    }
    synchronized (this) {
      present = nativeTracingPresent;
      if (present != null) {
        return present;
      }
      try {
        boolean found = context.hasService(CamelTracingService.class) != null;
        if (context.isStarted()) {
          nativeTracingPresent = found;
        }
        return found;
      } catch (Throwable ignored) {
        return true;
      }
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
    if (nativeTracingPresent() || !(event instanceof CamelEvent.ExchangeSendingEvent)) {
      return false;
    }
    Endpoint endpoint = ((CamelEvent.ExchangeSendingEvent) event).getEndpoint();
    Component component = endpoint == null ? null : endpoint.getComponent();
    String name = component == null ? null : component.getDefaultName();
    return !"direct".equals(name) && !"bean".equals(name) && !"log".equals(name);
  }

  @Override
  public void notify(CamelEvent event) {
    if (event instanceof CamelEvent.ExchangeSendingEvent) {
      CamelState.propagate(((CamelEvent.ExchangeSendingEvent) event).getExchange());
    }
  }

  public static final class Policy extends RoutePolicySupport
      implements CamelInternalProcessorAdvice<Object> {
    private final CamelTracingStrategy strategy;
    private Route route;
    private boolean completionRegistered;

    public Policy(CamelTracingStrategy strategy) {
      this.strategy = strategy;
    }

    @Override
    public void onInit(Route route) {
      this.route = route;
      try {
        Processor processor = route.getProcessor();
        if (processor instanceof InternalProcessor) {
          // Route-policy completion is skipped during shutdown; processor advice still runs.
          ((InternalProcessor) processor).addAdvice(this);
          completionRegistered = true;
        }
      } catch (Throwable ignored) {
        // Keep route-policy completion if processor advice cannot be installed.
      }
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
      if (!completionRegistered) {
        CamelState.complete(route, exchange);
      }
    }

    @Override
    public Object before(Exchange exchange) {
      return null;
    }

    @Override
    public void after(Exchange exchange, Object state) {
      CamelState.complete(route, exchange);
    }

    @Override
    public boolean hasState() {
      return false;
    }
  }
}
