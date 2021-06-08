package datadog.trace.api.gateway;

import java.util.LinkedList;
import java.util.List;

public class InstrGateway {

  private final List<RequestContextFactory> factories = new LinkedList<>();

  public interface RequestContextFactory {
    RequestContext createRequestContext();
  }

  /**
   * Method allows to register factory that allows to instantiate RequestContext
   * implementations from Event consumer
   */
  public void registerRequestContextFactory(RequestContextFactory factory) {
    factories.add(factory);
  }

  /**
   * Method to create new RequestContext with all nested implementations
   */
  public RequestContext createRequestContext() {
    RequestContextDelegator ctx = new RequestContextDelegator();

    for (RequestContextFactory callback : factories) {
      RequestContext delegate = callback.createRequestContext();
      ctx.addDelegate(delegate);
    }

    return ctx;
  }
}
