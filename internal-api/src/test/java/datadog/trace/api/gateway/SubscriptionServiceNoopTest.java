package datadog.trace.api.gateway;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SubscriptionServiceNoopTest {

  @Test
  @SuppressWarnings("unchecked")
  void registrationOnNoopImplementation() {
    SubscriptionService subscriptionService = SubscriptionService.SubscriptionServiceNoop.INSTANCE;
    Function<RequestContext, Flow<Void>> callback = mock(Function.class);

    Subscription subscription =
        subscriptionService.registerCallback(Events.get().requestHeaderDone(), callback);
    subscription.cancel();

    verifyNoInteractions(callback);
  }
}
