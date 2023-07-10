package datadog.trace.api.gateway;

/** The API used by the consumers to register callbacks. */
public interface SubscriptionService {
  <C> Subscription registerCallback(EventType<C> eventType, C callback);

  void reset();

  class SubscriptionServiceNoop implements SubscriptionService {
    public static final SubscriptionService INSTANCE = new SubscriptionServiceNoop();

    private SubscriptionServiceNoop() {}

    @Override
    public <C> Subscription registerCallback(EventType<C> eventType, C callback) {
      return Subscription.SubscriptionNoop.INSTANCE;
    }

    @Override
    public void reset() {}
  }
}
