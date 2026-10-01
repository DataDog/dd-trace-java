package com.datadog.featureflag;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.HttpResponseException;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.intake.Intake;
import java.io.IOException;
import java.net.ConnectException;
import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.RequestBody;

/** Posts Feature Flags events through the Datadog Agent event platform proxy. */
class EventProxy {
  private static final MediaType JSON = MediaType.parse("application/json");
  static final String EXPOSURES_ROUTE = "exposures";

  private final BackendApiFactory backendApiFactory;
  private final boolean directFallbackAvailable;
  private volatile BackendApi exposures;
  private volatile BackendApi flagEvaluations;
  private volatile boolean resolved;

  /**
   * @param backendApiFactory the factory of event platform proxy clients.
   * @param directFallbackAvailable whether the SDK falls back to direct intake when the proxy is
   *     unavailable, in which case the proxy must not retry what the intake would accept.
   */
  EventProxy(final BackendApiFactory backendApiFactory, final boolean directFallbackAvailable) {
    this.backendApiFactory = backendApiFactory;
    this.directFallbackAvailable = directFallbackAvailable;
  }

  /**
   * @return whether the Datadog Agent supports the event platform proxy.
   */
  boolean isAvailable() {
    resolve();
    return this.exposures != null;
  }

  /**
   * Posts a payload.
   *
   * @param route the event platform route.
   * @param json the UTF-8 JSON payload.
   * @return {@code true} if delivered, {@code false} if the proxy is definitively unavailable.
   * @throws IOException if the payload could not be delivered.
   */
  boolean post(final String route, final byte[] json) throws IOException {
    resolve();
    final BackendApi api = EXPOSURES_ROUTE.equals(route) ? this.exposures : this.flagEvaluations;
    if (api == null) {
      return false;
    }
    try {
      api.post(route, RequestBody.create(JSON, json), stream -> null, null, false);
      return true;
    } catch (final IOException e) {
      if (isDefinitiveRejection(e)) {
        return false;
      }
      throw e;
    }
  }

  private void resolve() {
    if (this.resolved) {
      return;
    }
    synchronized (this) {
      if (this.resolved) {
        return;
      }
      this.exposures = create(FeatureFlagEventType.EXPOSURE);
      this.flagEvaluations = create(FeatureFlagEventType.FLAG_EVALUATION);
      this.resolved = true;
    }
  }

  @Nullable
  private BackendApi create(final FeatureFlagEventType eventType) {
    return this.directFallbackAvailable
        ? this.backendApiFactory.createEvpProxyApi(
            Intake.EVENT_PLATFORM,
            eventType.responseCompressionEnabled(),
            HttpRetryPolicy.Factory.NEVER_RETRY)
        : this.backendApiFactory.createEvpProxyApi(
            Intake.EVENT_PLATFORM, eventType.responseCompressionEnabled());
  }

  static boolean isDefinitiveRejection(final IOException exception) {
    if (exception instanceof ConnectException) {
      return true;
    }
    if (exception instanceof HttpResponseException) {
      final int statusCode = ((HttpResponseException) exception).getStatusCode();
      return statusCode == 403 || statusCode == 404 || statusCode == 405;
    }
    return false;
  }
}
