package datadog.trace.api.gateway;

/** A handle to the started subscription of an event. */
public interface Subscription {
  void cancel();
}
