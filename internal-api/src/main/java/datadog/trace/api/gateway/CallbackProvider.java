package datadog.trace.api.gateway;

/** The API used by the producers to retrieve callbacks. */
public interface CallbackProvider {
  <C> C getCallback(EventType<C> eventType);
}
