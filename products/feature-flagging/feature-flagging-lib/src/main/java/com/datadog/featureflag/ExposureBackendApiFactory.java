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

/** Selects the transport for Feature Flagging exposure events. */
final class ExposureBackendApiFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExposureBackendApiFactory.class);

  private final Config config;
  private final BackendApiFactory backendApiFactory;

  ExposureBackendApiFactory(
      final Config config, final SharedCommunicationObjects sharedCommunicationObjects) {
    this(config, new BackendApiFactory(config, sharedCommunicationObjects));
  }

  ExposureBackendApiFactory(final Config config, final BackendApiFactory backendApiFactory) {
    this.config = config;
    this.backendApiFactory = backendApiFactory;
  }

  @Nullable
  BackendApi create() {
    final BackendApi localApi = backendApiFactory.createEvpProxyApi(Intake.EVENT_PLATFORM);
    if (!CONFIGURATION_SOURCE_AGENTLESS.equals(config.getFeatureFlaggingConfigurationSource())) {
      if (localApi == null) {
        LOGGER.warn(
            "Feature Flagging exposure delivery is disabled because the local Agent does not support the EVP proxy");
      }
      return localApi;
    }

    if (localApi != null) {
      if (hasDirectCredentials()) {
        return new AgentlessExposureBackendApi(localApi, this::createDirectApi);
      }
      return localApi;
    }

    final BackendApi directApi = createDirectApi();
    if (directApi != null) {
      return directApi;
    }

    LOGGER.warn(
        "Feature Flagging exposure delivery is disabled because no compatible local EVP proxy or direct intake credentials are available");
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
      return backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM);
    } catch (final IllegalArgumentException exception) {
      LOGGER.debug("Cannot configure direct Feature Flagging exposure delivery", exception);
      return null;
    }
  }
}
