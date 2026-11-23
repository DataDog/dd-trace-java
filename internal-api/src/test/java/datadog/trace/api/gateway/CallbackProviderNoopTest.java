package datadog.trace.api.gateway;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CallbackProviderNoopTest {

  @Test
  void getCallbackOnNoopImplementation() {
    CallbackProvider callbackProvider = CallbackProvider.CallbackProviderNoop.INSTANCE;

    Object callback = callbackProvider.getCallback(Events.get().requestHeaderDone());

    assertNull(callback);
  }
}
