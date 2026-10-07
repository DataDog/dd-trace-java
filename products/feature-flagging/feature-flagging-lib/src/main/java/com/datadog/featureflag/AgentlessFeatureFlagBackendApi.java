package com.datadog.featureflag;

import datadog.communication.BackendApi;
import datadog.communication.HttpResponseException;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.function.Function;
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
  private final Supplier<String> proxyEndpointSupplier;
  private final Function<String, BackendApi> proxyApiFactory;
  private final Supplier<BackendApi> directApiSupplier;
  private final String eventType;
  private BackendApi proxyApi;
  private FeatureFlagRouteSelector.LocalRoute proxyRoute;
  private volatile BackendApi directApi;
  private volatile boolean directApiCreationAttempted;

  AgentlessFeatureFlagBackendApi(
      @Nullable final BackendApi directApi,
      final Supplier<String> proxyEndpointSupplier,
      final Function<String, BackendApi> proxyApiFactory,
      final Supplier<BackendApi> directApiSupplier,
      final String eventType,
      final FeatureFlagRouteSelector routeSelector) {
    this.directApi = directApi;
    this.proxyEndpointSupplier = proxyEndpointSupplier;
    this.proxyApiFactory = proxyApiFactory;
    this.directApiSupplier = directApiSupplier;
    this.eventType = eventType;
    this.routeSelector = routeSelector;
    this.directApiCreationAttempted = directApi != null;
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
      if (selected.localRoute == null) {
        throw exception;
      }

      final BackendApi fallbackApi = getOrCreateDirectApi();
      // Route selection governs future batches; replay permission applies only to this batch.
      // DIRECT is intentionally terminal, even for a transient or ambiguous local failure.
      routeSelector.localFailure(selected.localRoute, fallbackApi != null);
      if (fallbackApi == null || !isSafeToReplayDirectly(exception)) {
        throw exception;
      }
      return fallbackApi.post(
          uri, requestBody, responseParser, requestListener, requestCompression);
    }
  }

  private SelectedApi selectApi() throws IOException {
    if (routeSelector.tryBeginLocalRecovery()) {
      routeSelector.localRecoveryFinished(discoverProxyEndpoint());
    }

    final FeatureFlagRouteSelector.LocalRoute localRoute = routeSelector.localRoute();
    if (localRoute != null) {
      return new SelectedApi(getOrCreateProxyApi(localRoute), localRoute);
    }

    if (routeSelector.current() == FeatureFlagRouteSelector.Route.DIRECT) {
      final BackendApi selectedDirectApi = getOrCreateDirectApi();
      if (selectedDirectApi != null) {
        return new SelectedApi(selectedDirectApi, null);
      }
    }
    throw unavailableRoute();
  }

  @Nullable
  private String discoverProxyEndpoint() {
    try {
      return proxyEndpointSupplier.get();
    } catch (final RuntimeException exception) {
      LOGGER.debug("Could not discover the local Feature Flagging {} route", eventType, exception);
      return null;
    }
  }

  private synchronized BackendApi getOrCreateProxyApi(
      final FeatureFlagRouteSelector.LocalRoute localRoute) {
    if (proxyRoute != localRoute) {
      // Client construction uses the shared validated endpoint; it performs no discovery.
      proxyApi = proxyApiFactory.apply(localRoute.endpoint);
      proxyRoute = localRoute;
    }
    return proxyApi;
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
    private final FeatureFlagRouteSelector.LocalRoute localRoute;

    private SelectedApi(
        final BackendApi api, @Nullable final FeatureFlagRouteSelector.LocalRoute localRoute) {
      this.api = api;
      this.localRoute = localRoute;
    }
  }
}
