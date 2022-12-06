package datadog.trace.api.gateway;

/** The API used by the producers to retrieve callbacks. */
public interface CallbackProvider {
  <C> C getCallback(EventType<C> eventType);

  class CallbackProviderNoop implements CallbackProvider {
    public static final CallbackProvider INSTANCE = new CallbackProviderNoop();

    private CallbackProviderNoop() {}

    @Override
    public <C> C getCallback(EventType<C> eventType) {
      return null;
    }
  }
}
