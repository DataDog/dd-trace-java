package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_REMOTE_CONFIG;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import org.junit.jupiter.api.Test;

class FeatureFlagBackendApiFactoryTest {

  @Test
  void remoteConfigUsesOnlyLocalEvpProxy() {
    final Config config = config(CONFIGURATION_SOURCE_REMOTE_CONFIG, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    final BackendApi localApi = mock(BackendApi.class);
    when(backendApiFactory.createEvpProxyApi(Intake.EVENT_PLATFORM, false)).thenReturn(localApi);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, "flag evaluation", false)
            .create();

    assertSame(localApi, selected);
    verify(backendApiFactory, never()).createDirectIntakeApi(Intake.EVENT_PLATFORM, false);
  }

  @Test
  void agentlessPrefersLocalEvpProxyWithDirectFallback() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    when(backendApiFactory.createEvpProxyApi(Intake.EVENT_PLATFORM, false))
        .thenReturn(mock(BackendApi.class));
    when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false))
        .thenReturn(mock(BackendApi.class));

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, "flag evaluation", false)
            .create();

    assertInstanceOf(AgentlessFeatureFlagBackendApi.class, selected);
  }

  @Test
  void agentlessUsesDirectIntakeWhenLocalEvpProxyIsUnavailable() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    final BackendApi directApi = mock(BackendApi.class);
    when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false))
        .thenReturn(directApi);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, "flag evaluation", false)
            .create();

    assertSame(directApi, selected);
  }

  @Test
  void agentlessUsesLocalEvpProxyWhenApiKeyIsUnavailable() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, null);
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    final BackendApi localApi = mock(BackendApi.class);
    when(backendApiFactory.createEvpProxyApi(Intake.EVENT_PLATFORM, false)).thenReturn(localApi);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, "flag evaluation", false)
            .create();

    assertSame(localApi, selected);
    verify(backendApiFactory, never()).createDirectIntakeApi(Intake.EVENT_PLATFORM, false);
  }

  @Test
  void agentlessDisablesDeliveryWhenNoRouteIsAvailable() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, null);
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, "flag evaluation", false)
            .create();

    assertNull(selected);
  }

  @Test
  void agentlessKeepsLocalRouteWhenDirectUrlIsInvalid() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    final BackendApi localApi = mock(BackendApi.class);
    when(backendApiFactory.createEvpProxyApi(Intake.EVENT_PLATFORM, false)).thenReturn(localApi);
    when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false))
        .thenThrow(new IllegalArgumentException("invalid URL"));

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, "flag evaluation", false)
            .create();

    assertSame(localApi, selected);
  }

  private static Config config(final String source, final String apiKey) {
    final Config config = mock(Config.class);
    when(config.getFeatureFlaggingConfigurationSource()).thenReturn(source);
    when(config.getApiKey()).thenReturn(apiKey);
    return config;
  }
}
