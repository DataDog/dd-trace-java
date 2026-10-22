package com.datadog.featureflag;

import static datadog.communication.EvpProxy.JAVA_TRACING_LIBRARY;
import static datadog.communication.EvpProxy.ORIGIN_HEADER;
import static datadog.communication.EvpProxy.ORIGIN_VERSION_HEADER;
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableMap;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.ddagent.TracerVersion;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import java.net.ConnectException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selects the transport for Feature Flagging events. */
final class FeatureFlagBackendApiFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlagBackendApiFactory.class);
  private static final Map<String, String> REQUEST_HEADERS = requestHeaders();

  /** Retries connection refusal only; automatic retries and redirects remain disabled. */
  static final HttpRetryPolicy.Factory PRE_SEND_RETRY =
      new HttpRetryPolicy.Factory(3, 100, 2.0) {
        @Override
        public HttpRetryPolicy create() {
          return new PreSendRetryPolicy();
        }
      };

  private static final class PreSendRetryPolicy extends HttpRetryPolicy {
    private int attemptsLeft = 3;

    private PreSendRetryPolicy() {
      super(3, 100, 2.0, false);
    }

    @Override
    public boolean shouldRetry(final Exception exception) {
      if (exception instanceof ConnectException && attemptsLeft > 0) {
        attemptsLeft--;
        return true;
      }
      return false;
    }

    @Override
    public boolean shouldRetry(@Nullable final Response response) {
      return false;
    }
  }

  private final Config config;
  private final BackendApiFactory backendApiFactory;
  private final FeatureFlagEventType eventType;
  private final FeatureFlagRouteSelector routeSelector;

  FeatureFlagBackendApiFactory(
      final Config config,
      final SharedCommunicationObjects sharedCommunicationObjects,
      final FeatureFlagEventType eventType) {
    this(config, sharedCommunicationObjects, eventType, new FeatureFlagRouteSelector());
  }

  FeatureFlagBackendApiFactory(
      final Config config,
      final SharedCommunicationObjects sharedCommunicationObjects,
      final FeatureFlagEventType eventType,
      final FeatureFlagRouteSelector routeSelector) {
    this(
        config,
        initializedBackendFactory(config, sharedCommunicationObjects),
        eventType,
        routeSelector);
  }

  private static BackendApiFactory initializedBackendFactory(
      final Config config, final SharedCommunicationObjects sharedCommunicationObjects) {
    if (CONFIGURATION_SOURCE_AGENTLESS.equals(config.getFeatureFlaggingConfigurationSource())
        && config.getApiKey() != null
        && !config.getApiKey().isEmpty()) {
      // Direct intake is constructed before Agent discovery. Initialize settings first so its
      // cached HTTP client uses configured timeouts even when Feature Flagging starts first.
      sharedCommunicationObjects.createRemaining(config);
    }
    return new BackendApiFactory(config, sharedCommunicationObjects, REQUEST_HEADERS, true);
  }

  FeatureFlagBackendApiFactory(
      final Config config,
      final BackendApiFactory backendApiFactory,
      final FeatureFlagEventType eventType) {
    this(config, backendApiFactory, eventType, new FeatureFlagRouteSelector());
  }

  FeatureFlagBackendApiFactory(
      final Config config,
      final BackendApiFactory backendApiFactory,
      final FeatureFlagEventType eventType,
      final FeatureFlagRouteSelector routeSelector) {
    this.config = config;
    this.backendApiFactory = backendApiFactory;
    this.eventType = eventType;
    this.routeSelector = routeSelector;
  }

  @Nullable
  BackendApi create() {
    final boolean agentless =
        CONFIGURATION_SOURCE_AGENTLESS.equals(config.getFeatureFlaggingConfigurationSource());
    if (!agentless) {
      // Remote Config stays Agent-only. Fixed v2 intentionally replaces Java's previous
      // discovered endpoint; only connection refusal retains a bounded retry.
      return backendApiFactory.createEvpProxyApiForEndpoint(
          Intake.EVENT_PLATFORM,
          eventType.responseCompressionEnabled(),
          PRE_SEND_RETRY,
          V2_EVP_PROXY_ENDPOINT);
    }

    final BackendApi directApi = createDirectApi();
    routeSelector.initialize(() -> discoverProxyEndpoint(false), directApi != null);
    return new AgentlessFeatureFlagBackendApi(
        directApi,
        () -> discoverProxyEndpoint(true),
        endpoint ->
            backendApiFactory.createEvpProxyApiForEndpoint(
                Intake.EVENT_PLATFORM,
                eventType.responseCompressionEnabled(),
                HttpRetryPolicy.Factory.NEVER_RETRY,
                endpoint),
        this::createDirectApi,
        eventType.logName(),
        routeSelector);
  }

  @Nullable
  private String discoverProxyEndpoint(final boolean forceDiscovery) {
    try {
      // A local route must preserve the identity headers that this sender emits on every batch.
      return backendApiFactory.discoverEvpProxyEndpoint(
          forceDiscovery, asList(ORIGIN_HEADER, ORIGIN_VERSION_HEADER));
    } catch (final RuntimeException exception) {
      LOGGER.debug("Could not discover the local Feature Flagging route", exception);
      return null;
    }
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
