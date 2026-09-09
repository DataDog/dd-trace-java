package com.datadog.featureflag;

import static datadog.communication.EvpProxy.JAVA_TRACING_LIBRARY;
import static datadog.communication.EvpProxy.ORIGIN_HEADER;
import static datadog.communication.EvpProxy.ORIGIN_VERSION_HEADER;
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static java.util.Collections.unmodifiableMap;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.ddagent.TracerVersion;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selects the transport for Feature Flagging events. */
final class FeatureFlagBackendApiFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlagBackendApiFactory.class);
  private static final Map<String, String> REQUEST_HEADERS = requestHeaders();

  private final Config config;
  private final BackendApiFactory backendApiFactory;
  private final FeatureFlagEventType eventType;

  FeatureFlagBackendApiFactory(
      final Config config,
      final SharedCommunicationObjects sharedCommunicationObjects,
      final FeatureFlagEventType eventType) {
    this(
        config,
        new BackendApiFactory(config, sharedCommunicationObjects, REQUEST_HEADERS, true),
        eventType);
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
    final boolean agentless =
        CONFIGURATION_SOURCE_AGENTLESS.equals(config.getFeatureFlaggingConfigurationSource());
    final boolean directFallbackAvailable = agentless && hasDirectCredentials();
    // Preserve the historical v2 endpoint when initial discovery itself is unavailable. Recovery
    // from a working direct route is stricter below: only an advertised endpoint proves that local
    // delivery has returned, avoiding an ambiguous failed probe of an assumed v2 endpoint.
    final BackendApi proxyApi = createProxyApi(false, true);
    if (!agentless) {
      if (proxyApi == null) {
        LOGGER.warn(
            "Feature Flagging {} delivery is disabled because the local Agent does not support the EVP proxy",
            eventType.logName());
      }
      return proxyApi;
    }

    if (!directFallbackAvailable) {
      return proxyApi;
    }

    final BackendApi directApi = proxyApi == null ? createDirectApi() : null;
    if (proxyApi != null || directApi != null) {
      return new AgentlessFeatureFlagBackendApi(
          proxyApi,
          directApi,
          () -> createProxyApi(true, false),
          this::createDirectApi,
          eventType.logName());
    }

    LOGGER.warn(
        "Feature Flagging {} delivery is disabled because no compatible local EVP proxy or direct intake credentials are available",
        eventType.logName());
    return null;
  }

  @Nullable
  private BackendApi createProxyApi(
      final boolean forceDiscovery, final boolean useDiscoveryFailureFallback) {
    return backendApiFactory.createEvpProxyApi(
        Intake.EVENT_PLATFORM,
        eventType.responseCompressionEnabled(),
        HttpRetryPolicy.Factory.NEVER_RETRY,
        useDiscoveryFailureFallback ? V2_EVP_PROXY_ENDPOINT : null,
        forceDiscovery);
  }

  private static Map<String, String> requestHeaders() {
    final Map<String, String> headers = new HashMap<>(2);
    headers.put(ORIGIN_HEADER, JAVA_TRACING_LIBRARY);
    headers.put(ORIGIN_VERSION_HEADER, TracerVersion.TRACER_VERSION);
    return unmodifiableMap(headers);
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
