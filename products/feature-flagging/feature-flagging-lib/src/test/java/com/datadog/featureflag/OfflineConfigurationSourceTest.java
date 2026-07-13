package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OfflineConfigurationSourceTest {

  private final FeatureFlaggingGateway.ConfigListener configListener =
      mock(FeatureFlaggingGateway.ConfigListener.class);
  private final OfflineConfigurationSource source = new OfflineConfigurationSource();

  @AfterEach
  void cleanup() {
    source.close();
    FeatureFlaggingGateway.removeConfigListener(configListener);
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
  }

  @Test
  void appliesStartupConfigurationExactlyOnce() {
    source.init();
    FeatureFlaggingGateway.addConfigListener(configListener);

    assertTrue(FeatureFlaggingGateway.dispatchOfflineConfiguration(emptyConfiguration()));
    assertThrows(
        IllegalStateException.class,
        () -> FeatureFlaggingGateway.dispatchOfflineConfiguration(emptyConfiguration()));

    verify(configListener, times(1)).accept(any(ServerConfiguration.class));
  }

  @Test
  void invalidConfigurationDoesNotPreventValidStartupConfiguration() {
    source.init();
    FeatureFlaggingGateway.addConfigListener(configListener);

    assertThrows(
        IllegalArgumentException.class,
        () -> FeatureFlaggingGateway.dispatchOfflineConfiguration("{".getBytes(UTF_8)));
    verifyNoInteractions(configListener);

    assertTrue(FeatureFlaggingGateway.dispatchOfflineConfiguration(emptyConfiguration()));
    verify(configListener).accept(any(ServerConfiguration.class));
  }

  @Test
  void repeatedInitRegistersOneListenerAndCloseUnregistersIt() {
    source.init();
    source.init();

    assertTrue(FeatureFlaggingGateway.dispatchOfflineConfiguration(emptyConfiguration()));

    source.close();
    assertFalse(FeatureFlaggingGateway.dispatchOfflineConfiguration(emptyConfiguration()));
  }

  private static byte[] emptyConfiguration() {
    return ("{"
            + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
            + "\"format\":\"SERVER\","
            + "\"environment\":{\"name\":\"Test\"},"
            + "\"flags\":{}"
            + "}")
        .getBytes(UTF_8);
  }
}
