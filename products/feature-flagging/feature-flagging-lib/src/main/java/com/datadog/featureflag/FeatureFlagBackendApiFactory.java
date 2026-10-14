package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selects the transport for Feature Flagging events. */
final class FeatureFlagBackendApiFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlagBackendApiFactory.class);

  private final Config config;
  private final BackendApiFactory backendApiFactory;
  private final FeatureFlagEventType eventType;

  FeatureFlagBackendApiFactory(
      final Config config,
      final SharedCommunicationObjects sharedCommunicationObjects,
      final FeatureFlagEventType eventType) {
    this(config, new BackendApiFactory(config, sharedCommunicationObjects), eventType);
  }

  FeatureFlagBackendApiFactory(
      final Config config,
      final BackendApiFactory backendApiFactory,
      final FeatureFlagEventType eventType) {
    this.config = config;
    this.backendApiFactory = backendApiFactory;
    this.eventType = eventType;
  }

  @Nullable
  BackendApi create() {
    final boolean directFallbackAvailable =
        CONFIGURATION_SOURCE_AGENTLESS.equals(config.getFeatureFlaggingConfigurationSource())
            && hasDirectCredentials();
    final BackendApi proxyApi =
        directFallbackAvailable
            ? backendApiFactory.createEvpProxyApi(
                Intake.EVENT_PLATFORM,
                eventType.responseCompressionEnabled(),
                HttpRetryPolicy.Factory.NEVER_RETRY)
            : backendApiFactory.createEvpProxyApi(
                Intake.EVENT_PLATFORM, eventType.responseCompressionEnabled());
    if (!CONFIGURATION_SOURCE_AGENTLESS.equals(config.getFeatureFlaggingConfigurationSource())) {
      if (proxyApi == null) {
        LOGGER.warn(
            "Feature Flagging {} delivery is disabled because the local Agent does not support the EVP proxy",
            eventType.logName());
      }
      return proxyApi;
    }

    if (proxyApi != null) {
      if (directFallbackAvailable) {
        return new AgentlessFeatureFlagBackendApi(
            proxyApi, this::createDirectApi, eventType.logName());
      }
      return proxyApi;
    }

    final BackendApi directApi = createDirectApi();
    if (directApi != null) {
      return directApi;
    }

    LOGGER.warn(
        "Feature Flagging {} delivery is disabled because no compatible local EVP proxy or direct intake credentials are available",
        eventType.logName());
    return null;
  }

  private boolean hasDirectCredentials() {
    final String apiKey = config.getApiKey();
    return apiKey != null && !apiKey.isEmpty();
  }

  @Nullable
  private BackendApi createDirectApi() {
    if (!hasDirectCredentials()) {
      return null;
    }
    try {
      return backendApiFactory.createDirectIntakeApi(
          Intake.EVENT_PLATFORM, eventType.responseCompressionEnabled(), false);
    } catch (final IllegalArgumentException exception) {
      LOGGER.debug(
          "Cannot configure direct Feature Flagging {} delivery", eventType.logName(), exception);
      return null;
    }
  }
}
