package com.datadog.featureflag;

import datadog.communication.BackendApi;
import datadog.communication.HttpResponseException;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends Feature Flag events through a local EVP proxy, with a safe direct intake fallback. */
final class AgentlessFeatureFlagBackendApi implements BackendApi {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(AgentlessFeatureFlagBackendApi.class);
  private static final long DEFAULT_RECOVERY_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  private final Supplier<BackendApi> proxyApiSupplier;
  private final Supplier<BackendApi> directApiSupplier;
  private final String eventType;
  private final LongSupplier nanoTime;
  private final long recoveryIntervalNanos;
  private volatile Route activeRoute;
  private volatile BackendApi directApi;
  private volatile boolean directApiCreationAttempted;
  private volatile long nextProxyProbeNanos;

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
        System::nanoTime,
        DEFAULT_RECOVERY_INTERVAL_NANOS);
  }

  AgentlessFeatureFlagBackendApi(
      @Nullable final BackendApi proxyApi,
      @Nullable final BackendApi directApi,
      final Supplier<BackendApi> proxyApiSupplier,
      final Supplier<BackendApi> directApiSupplier,
      final String eventType,
      final LongSupplier nanoTime,
      final long recoveryIntervalNanos) {
    if (proxyApi == null && directApi == null) {
      throw new IllegalArgumentException("A Feature Flagging event route is required");
    }
    this.proxyApiSupplier = proxyApiSupplier;
    this.directApi = directApi;
    this.directApiSupplier = directApiSupplier;
    this.eventType = eventType;
    this.nanoTime = nanoTime;
    this.recoveryIntervalNanos = recoveryIntervalNanos;
    this.activeRoute = proxyApi != null ? new Route(proxyApi, true) : new Route(directApi, false);
    this.directApiCreationAttempted = directApi != null;
    if (proxyApi == null) {
      scheduleProxyRecovery();
    }
  }

  @Override
  public <T> T post(
      final String uri,
      final RequestBody requestBody,
      final IOThrowingFunction<InputStream, T> responseParser,
      @Nullable final OkHttpUtils.CustomListener requestListener,
      final boolean requestCompression)
      throws IOException {
    final Route selectedRoute = selectRoute();
    try {
      return selectedRoute.api.post(
          uri, requestBody, responseParser, requestListener, requestCompression);
    } catch (final IOException exception) {
      if (!selectedRoute.proxy) {
        throw exception;
      }

      final BackendApi fallbackApi = switchFutureBatchesToDirect(selectedRoute);
      if (fallbackApi == null || !isSafeToReplayDirectly(exception)) {
        throw exception;
      }
      return fallbackApi.post(
          uri, requestBody, responseParser, requestListener, requestCompression);
    }
  }

  private Route selectRoute() {
    final Route selectedRoute = activeRoute;
    if (selectedRoute.proxy || !proxyRecoveryDue()) {
      return selectedRoute;
    }

    synchronized (this) {
      final Route currentRoute = activeRoute;
      if (currentRoute.proxy || !proxyRecoveryDue()) {
        return currentRoute;
      }
      // Reserve the next recovery window before performing discovery so concurrent senders keep
      // using direct intake instead of blocking or creating a probe stampede.
      scheduleProxyRecovery();
    }

    BackendApi recoveredProxyApi = null;
    try {
      recoveredProxyApi = proxyApiSupplier.get();
    } catch (final RuntimeException exception) {
      // Route recovery is best effort. A discovery/configuration failure must not interrupt the
      // working direct route and lose the current batch.
      LOGGER.debug("Could not recover the local Feature Flagging {} route", eventType, exception);
    }
    if (recoveredProxyApi != null) {
      synchronized (this) {
        if (!activeRoute.proxy) {
          LOGGER.debug(
              "Switching Feature Flagging {} delivery from direct intake to the local EVP proxy",
              eventType);
          activeRoute = new Route(recoveredProxyApi, true);
        }
      }
    }
    return activeRoute;
  }

  @Nullable
  private BackendApi switchFutureBatchesToDirect(final Route failedProxyRoute) {
    final BackendApi fallbackApi = getOrCreateDirectApi();
    if (fallbackApi == null) {
      return null;
    }

    synchronized (this) {
      if (activeRoute == failedProxyRoute) {
        LOGGER.debug(
            "Switching Feature Flagging {} delivery from the local EVP proxy to direct intake",
            eventType);
        activeRoute = new Route(fallbackApi, false);
        scheduleProxyRecovery();
      }
    }
    return fallbackApi;
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

  private boolean proxyRecoveryDue() {
    return nanoTime.getAsLong() - nextProxyProbeNanos >= 0;
  }

  private void scheduleProxyRecovery() {
    nextProxyProbeNanos = nanoTime.getAsLong() + recoveryIntervalNanos;
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

  private static final class Route {
    private final BackendApi api;
    private final boolean proxy;

    private Route(final BackendApi api, final boolean proxy) {
      this.api = api;
      this.proxy = proxy;
    }
  }
}
