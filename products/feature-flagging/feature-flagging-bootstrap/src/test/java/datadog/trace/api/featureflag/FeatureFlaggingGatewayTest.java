package datadog.trace.api.featureflag;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

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
}
