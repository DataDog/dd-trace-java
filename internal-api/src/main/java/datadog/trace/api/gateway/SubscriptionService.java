package datadog.trace.api.gateway;

/** The API used by the consumers to register callbacks. */
public interface SubscriptionService {
  <C> Subscription registerCallback(EventType<C> eventType, C callback);
}
