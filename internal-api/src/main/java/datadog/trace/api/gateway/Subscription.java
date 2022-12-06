package datadog.trace.api.gateway;

/** A handle to the started subscription of an event. */
public interface Subscription {
  void cancel();

  class SubscriptionNoop implements Subscription {
    public static final Subscription INSTANCE = new SubscriptionNoop();

    private SubscriptionNoop() {}

    @Override
    public void cancel() {}
  }
}
