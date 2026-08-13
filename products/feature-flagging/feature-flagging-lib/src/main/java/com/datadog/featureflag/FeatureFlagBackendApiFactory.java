package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
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
  private final String eventType;
  private final boolean responseCompression;

  FeatureFlagBackendApiFactory(
      final Config config,
      final SharedCommunicationObjects sharedCommunicationObjects,
      final String eventType,
      final boolean responseCompression) {
    this(
        config,
        new BackendApiFactory(config, sharedCommunicationObjects),
        eventType,
        responseCompression);
  }

  FeatureFlagBackendApiFactory(
      final Config config,
      final BackendApiFactory backendApiFactory,
      final String eventType,
      final boolean responseCompression) {
    this.config = config;
    this.backendApiFactory = backendApiFactory;
    this.eventType = eventType;
    this.responseCompression = responseCompression;
  }

  @Nullable
  BackendApi create() {
    final BackendApi localApi =
        backendApiFactory.createEvpProxyApi(Intake.EVENT_PLATFORM, responseCompression);
    if (!CONFIGURATION_SOURCE_AGENTLESS.equals(config.getFeatureFlaggingConfigurationSource())) {
      if (localApi == null) {
        LOGGER.warn(
            "Feature Flagging {} delivery is disabled because the local Agent does not support the EVP proxy",
            eventType);
      }
      return localApi;
    }

    if (localApi != null) {
      if (hasDirectCredentials()) {
        return new AgentlessFeatureFlagBackendApi(localApi, this::createDirectApi, eventType);
      }
      return localApi;
    }

    final BackendApi directApi = createDirectApi();
    if (directApi != null) {
      return directApi;
    }

    LOGGER.warn(
        "Feature Flagging {} delivery is disabled because no compatible local EVP proxy or direct intake credentials are available",
        eventType);
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
      return backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, responseCompression);
    } catch (final IllegalArgumentException exception) {
      LOGGER.debug("Cannot configure direct Feature Flagging {} delivery", eventType, exception);
      return null;
    }
  }
}
