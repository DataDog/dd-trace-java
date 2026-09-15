package com.datadog.featureflag;

import static com.datadog.featureflag.FeatureFlagEventType.EXPOSURE;
import static com.datadog.featureflag.FeatureFlagEventType.FLAG_EVALUATION;
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_REMOTE_CONFIG;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
  void remoteConfigUsesFixedV2WithoutDiscoveryOrDirectIntake() {
    final Config config = config(CONFIGURATION_SOURCE_REMOTE_CONFIG, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    final BackendApi proxyApi = mock(BackendApi.class);
    when(backendApiFactory.createEvpProxyApiForEndpoint(
            Intake.EVENT_PLATFORM,
            false,
            HttpRetryPolicy.Factory.NEVER_RETRY,
            V2_EVP_PROXY_ENDPOINT))
        .thenReturn(proxyApi);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertSame(proxyApi, selected);
    verify(backendApiFactory, never())
        .createEvpProxyApi(
            Intake.EVENT_PLATFORM, false, HttpRetryPolicy.Factory.NEVER_RETRY, false, true);
    verify(backendApiFactory, never()).createDirectIntakeApi(Intake.EVENT_PLATFORM, false, false);
  }

  @Test
  void agentlessPrefersCapabilityGatedLocalRouteWithDirectFallbackReady() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    when(backendApiFactory.createEvpProxyApi(
            Intake.EVENT_PLATFORM, false, HttpRetryPolicy.Factory.NEVER_RETRY, false, true))
        .thenReturn(mock(BackendApi.class));
    when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false, false))
        .thenReturn(mock(BackendApi.class));

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertInstanceOf(AgentlessFeatureFlagBackendApi.class, selected);
    verify(backendApiFactory)
        .createEvpProxyApi(
            Intake.EVENT_PLATFORM, false, HttpRetryPolicy.Factory.NEVER_RETRY, false, true);
    verify(backendApiFactory).createDirectIntakeApi(Intake.EVENT_PLATFORM, false, false);
  }

  @Test
  void agentlessUsesDirectIntakeWhenLocalRouteIsUnavailable() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false, false))
        .thenReturn(mock(BackendApi.class));

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertInstanceOf(AgentlessFeatureFlagBackendApi.class, selected);
  }

  @Test
  void agentlessKeepsWriterAliveWhileEveryRouteIsUnavailable() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, null);
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, EXPOSURE).create();

    assertInstanceOf(AgentlessFeatureFlagBackendApi.class, selected);
    verify(backendApiFactory, never()).createDirectIntakeApi(Intake.EVENT_PLATFORM, true, false);
  }

  @Test
  void agentlessKeepsCompatibleLocalRouteWhenDirectUrlIsInvalid() {
    final Config config = config(CONFIGURATION_SOURCE_AGENTLESS, "api-key");
    final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
    final BackendApi proxyApi = mock(BackendApi.class);
    when(backendApiFactory.createEvpProxyApi(
            Intake.EVENT_PLATFORM, false, HttpRetryPolicy.Factory.NEVER_RETRY, false, true))
        .thenReturn(proxyApi);
    when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false, false))
        .thenThrow(new IllegalArgumentException("invalid URL"));

    final BackendApi selected =
        new FeatureFlagBackendApiFactory(config, backendApiFactory, FLAG_EVALUATION).create();

    assertInstanceOf(AgentlessFeatureFlagBackendApi.class, selected);
  }

  private static Config config(final String source, final String apiKey) {
    final Config config = mock(Config.class);
    when(config.getFeatureFlaggingConfigurationSource()).thenReturn(source);
    when(config.getApiKey()).thenReturn(apiKey);
    return config;
  }
}
