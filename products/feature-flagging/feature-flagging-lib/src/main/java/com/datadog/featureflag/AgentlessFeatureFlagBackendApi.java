package com.datadog.featureflag;

import datadog.communication.BackendApi;
import datadog.communication.HttpResponseException;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends Feature Flag events through the process-wide Agentless EVP route selector. */
final class AgentlessFeatureFlagBackendApi implements BackendApi {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(AgentlessFeatureFlagBackendApi.class);

  private final FeatureFlagRouteSelector routeSelector;
  private final Supplier<BackendApi> proxyApiSupplier;
  private final Supplier<BackendApi> directApiSupplier;
  private final String eventType;
  private volatile BackendApi proxyApi;
  private volatile BackendApi directApi;
  private volatile boolean directApiCreationAttempted;

  AgentlessFeatureFlagBackendApi(
      @Nullable final BackendApi proxyApi,
      @Nullable final BackendApi directApi,
      final Supplier<BackendApi> proxyApiSupplier,
      final Supplier<BackendApi> directApiSupplier,
      final String eventType) {
    this(
        proxyApi,
        directApi,
        proxyApiSupplier,
        directApiSupplier,
        eventType,
        new FeatureFlagRouteSelector());
  }

  AgentlessFeatureFlagBackendApi(
      @Nullable final BackendApi proxyApi,
      @Nullable final BackendApi directApi,
      final Supplier<BackendApi> proxyApiSupplier,
      final Supplier<BackendApi> directApiSupplier,
      final String eventType,
      final LongSupplier nanoTime,
      final long recoveryIntervalNanos) {
    this(
        proxyApi,
        directApi,
        proxyApiSupplier,
        directApiSupplier,
        eventType,
        new FeatureFlagRouteSelector(nanoTime, recoveryIntervalNanos));
  }

  AgentlessFeatureFlagBackendApi(
      @Nullable final BackendApi proxyApi,
      @Nullable final BackendApi directApi,
      final Supplier<BackendApi> proxyApiSupplier,
      final Supplier<BackendApi> directApiSupplier,
      final String eventType,
      final FeatureFlagRouteSelector routeSelector) {
    this.proxyApi = proxyApi;
    this.directApi = directApi;
    this.proxyApiSupplier = proxyApiSupplier;
    this.directApiSupplier = directApiSupplier;
    this.eventType = eventType;
    this.routeSelector = routeSelector;
    this.directApiCreationAttempted = directApi != null;
    routeSelector.initialize(proxyApi != null, directApi != null);
  }

  @Override
  public <T> T post(
      final String uri,
      final RequestBody requestBody,
      final IOThrowingFunction<InputStream, T> responseParser,
      @Nullable final OkHttpUtils.CustomListener requestListener,
      final boolean requestCompression)
      throws IOException {
    final SelectedApi selected = selectApi();
    try {
      return selected.api.post(
          uri, requestBody, responseParser, requestListener, requestCompression);
    } catch (final IOException exception) {
      if (!selected.local) {
        throw exception;
      }

      final BackendApi fallbackApi = getOrCreateDirectApi();
      routeSelector.localFailure(fallbackApi != null);
      if (fallbackApi == null || !isSafeToReplayDirectly(exception)) {
        throw exception;
      }
      return fallbackApi.post(
          uri, requestBody, responseParser, requestListener, requestCompression);
    }
  }

  private SelectedApi selectApi() throws IOException {
    FeatureFlagRouteSelector.Route selectedRoute = routeSelector.current();
    if (selectedRoute == FeatureFlagRouteSelector.Route.UNAVAILABLE
        && routeSelector.tryBeginLocalRecovery()) {
      final BackendApi recoveredProxyApi = discoverProxyApi();
      if (recoveredProxyApi != null) {
        proxyApi = recoveredProxyApi;
        routeSelector.localRecovered();
      }
      selectedRoute = routeSelector.current();
    }

    if (selectedRoute == FeatureFlagRouteSelector.Route.LOCAL) {
      BackendApi selectedProxyApi = proxyApi;
      if (selectedProxyApi == null) {
        selectedProxyApi = discoverProxyApi();
        if (selectedProxyApi != null) {
          proxyApi = selectedProxyApi;
        } else {
          final BackendApi selectedDirectApi = getOrCreateDirectApi();
          routeSelector.localFailure(selectedDirectApi != null);
          if (selectedDirectApi != null) {
            return new SelectedApi(selectedDirectApi, false);
          }
          throw unavailableRoute();
        }
      }
      return new SelectedApi(selectedProxyApi, true);
    }

    if (selectedRoute == FeatureFlagRouteSelector.Route.DIRECT) {
      final BackendApi selectedDirectApi = getOrCreateDirectApi();
      if (selectedDirectApi != null) {
        return new SelectedApi(selectedDirectApi, false);
      }
    }
    throw unavailableRoute();
  }

  @Nullable
  private BackendApi discoverProxyApi() {
    try {
      return proxyApiSupplier.get();
    } catch (final RuntimeException exception) {
      LOGGER.debug("Could not discover the local Feature Flagging {} route", eventType, exception);
      return null;
    }
  }

  @Nullable
  private BackendApi getOrCreateDirectApi() {
    if (directApiCreationAttempted) {
      return directApi;
    }
    synchronized (this) {
      if (!directApiCreationAttempted) {
        directApi = directApiSupplier.get();
        directApiCreationAttempted = true;
      }
      return directApi;
    }
  }

  private IOException unavailableRoute() {
    return new IOException("No Feature Flagging " + eventType + " delivery route is available");
  }

  private static boolean isSafeToReplayDirectly(final IOException exception) {
    if (exception instanceof ConnectException) {
      return true;
    }
    if (exception instanceof HttpResponseException) {
      final int statusCode = ((HttpResponseException) exception).getStatusCode();
      return statusCode == 404 || statusCode == 405;
    }
    return false;
  }

  private static final class SelectedApi {
    private final BackendApi api;
    private final boolean local;

    private SelectedApi(final BackendApi api, final boolean local) {
      this.api = api;
      this.local = local;
    }
  }
}
