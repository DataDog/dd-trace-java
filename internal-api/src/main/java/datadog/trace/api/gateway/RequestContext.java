package datadog.trace.api.gateway;

/**
 * This is the context that will travel along with the request and be presented to the
 * Instrumentation Gateway subscribers.
 */
public interface RequestContext<D> {
  D getData();
}
