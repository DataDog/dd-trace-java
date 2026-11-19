package datadog.trace.api.featureflag;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FeatureFlaggingGatewayTest {
  @AfterEach
  void tearDown() {
    FeatureFlaggingGateway.register(null);
  }

  @Test
  void registersAndUnregistersTheBackend() {
    assertNull(FeatureFlaggingGateway.backend());
    final FeatureFlaggingGateway.Backend backend = mock(FeatureFlaggingGateway.Backend.class);
    FeatureFlaggingGateway.register(backend);
    assertSame(backend, FeatureFlaggingGateway.backend());
    FeatureFlaggingGateway.register(null);
    assertNull(FeatureFlaggingGateway.backend());
  }

  @Test
  void capabilitySubscriptionDefaultsToTheCompatibleSubscriptionMethod() {
    final FeatureFlaggingGateway.Backend backend =
        mock(FeatureFlaggingGateway.Backend.class, CALLS_REAL_METHODS);
    @SuppressWarnings("unchecked")
    final Consumer<byte[]> listener = mock(Consumer.class);
    final AutoCloseable subscription = mock(AutoCloseable.class);
    doReturn(subscription).when(backend).subscribeRemoteConfig(listener);

    assertSame(
        subscription,
        backend.subscribeRemoteConfig(listener, Collections.singleton("optional-capability")));
    verify(backend).subscribeRemoteConfig(listener);
  }
}
