package com.datadog.featureflag;

import static com.datadog.featureflag.FeatureFlagEventType.EXPOSURE;
import static com.datadog.featureflag.FeatureFlagEventType.FLAG_EVALUATION;
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
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import org.junit.jupiter.api.Test;

class FeatureFlagBackendApiFactoryTest {

  @Test
  void remoteConfigUsesOnlyLocalEvpProxy() {
    final Config config = config(CONFIGURATION_SOURCE_REMOTE_CONFIG, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    final BackendApi proxyApi = mock(BackendApi.class);
    when(backendApiFactory.createEvpProxyApi(Intake.EVENT_PLATFORM, false)).thenReturn(proxyApi);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertSame(proxyApi, selected);
    verify(backendApiFactory, never()).createDirectIntakeApi(Intake.EVENT_PLATFORM, false);
  }

  @Test
  void remoteConfigDisablesDeliveryWhenLocalEvpProxyIsUnavailable() {
    final Config config = config(CONFIGURATION_SOURCE_REMOTE_CONFIG, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, EXPOSURE).create();

    assertNull(selected);
    verify(backendApiFactory).createEvpProxyApi(Intake.EVENT_PLATFORM, true);
    verify(backendApiFactory, never()).createDirectIntakeApi(Intake.EVENT_PLATFORM, true);
  }

  @Test
  void agentlessPrefersLocalEvpProxyWithDirectFallback() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    when(backendApiFactory.createEvpProxyApi(
            Intake.EVENT_PLATFORM, false, HttpRetryPolicy.Factory.NEVER_RETRY))
        .thenReturn(mock(BackendApi.class));
    when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false))
        .thenReturn(mock(BackendApi.class));

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertInstanceOf(AgentlessFeatureFlagBackendApi.class, selected);
    verify(backendApiFactory)
        .createEvpProxyApi(Intake.EVENT_PLATFORM, false, HttpRetryPolicy.Factory.NEVER_RETRY);
    verify(backendApiFactory, never()).createDirectIntakeApi(Intake.EVENT_PLATFORM, false);
  }

  @Test
  void agentlessUsesDirectIntakeWhenLocalEvpProxyIsUnavailable() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    final BackendApi directApi = mock(BackendApi.class);
    when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false))
        .thenReturn(directApi);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertSame(directApi, selected);
  }

  @Test
  void agentlessUsesLocalEvpProxyWhenApiKeyIsUnavailable() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, null);
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    final BackendApi proxyApi = mock(BackendApi.class);
    when(backendApiFactory.createEvpProxyApi(Intake.EVENT_PLATFORM, false)).thenReturn(proxyApi);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertSame(proxyApi, selected);
    verify(backendApiFactory, never()).createDirectIntakeApi(Intake.EVENT_PLATFORM, false);
  }

  @Test
  void agentlessDisablesDeliveryWhenNoRouteIsAvailable() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, null);
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertNull(selected);
  }

  @Test
  void agentlessDisablesDeliveryWhenApiKeyIsEmpty() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, EXPOSURE).create();

    assertNull(selected);
    verify(backendApiFactory, never()).createDirectIntakeApi(Intake.EVENT_PLATFORM, true);
  }

  @Test
  void agentlessDoesNotValidateDirectUrlWhileLocalRouteIsAvailable() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    final BackendApi proxyApi = mock(BackendApi.class);
    when(backendApiFactory.createEvpProxyApi(
            Intake.EVENT_PLATFORM, false, HttpRetryPolicy.Factory.NEVER_RETRY))
        .thenReturn(proxyApi);
    when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false))
        .thenThrow(new IllegalArgumentException("invalid URL"));

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertInstanceOf(AgentlessFeatureFlagBackendApi.class, selected);
    verify(backendApiFactory, never()).createDirectIntakeApi(Intake.EVENT_PLATFORM, false);
  }

  @Test
  void agentlessDisablesDeliveryWhenDirectUrlIsInvalidAndLocalRouteIsUnavailable() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false))
        .thenThrow(new IllegalArgumentException("invalid URL"));

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertNull(selected);
  }

  private static Config config(final String source, final String apiKey) {
    final Config config = mock(Config.class);
    when(config.getFeatureFlaggingConfigurationSource()).thenReturn(source);
    when(config.getApiKey()).thenReturn(apiKey);
    return config;
  }
}
