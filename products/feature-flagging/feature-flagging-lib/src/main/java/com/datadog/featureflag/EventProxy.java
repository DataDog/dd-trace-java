package com.datadog.featureflag;

import static com.datadog.featureflag.FeatureFlagEventType.EXPOSURE;
import static com.datadog.featureflag.FeatureFlagEventType.FLAG_EVALUATION;
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT;
import static datadog.communication.http.HttpRetryPolicy.Factory.NEVER_RETRY;
import static datadog.trace.api.intake.Intake.EVENT_PLATFORM;
import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MINUTES;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.HttpResponseException;
import datadog.communication.http.HttpRetryPolicy;
import java.io.IOException;
import java.net.ConnectException;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts Feature Flags events through the Datadog Agent event platform proxy.
 *
 * <p>With the Remote Configuration source, events always go to the Agent through the fixed v2 proxy
 * endpoint. With the agentless source, the proxy endpoint is discovered once and shared by both
 * routes. When it is not available and the SDK cannot fall back to direct intake, the proxy route
 * stays in place and the endpoint is discovered again at most once per recovery interval.
 *
 * <p>Payloads are sent once: only a connection refusal, proven to happen before sending, is
 * retried, and only with the Remote Configuration source.
 */
class EventProxy {
  private static final Logger LOGGER = LoggerFactory.getLogger(EventProxy.class);
  private static final MediaType JSON = MediaType.parse("application/json");
  static final String EXPOSURES_ROUTE = "exposures";
  static final long RECOVERY_INTERVAL_NANOS = MINUTES.toNanos(1);

  /** Retries connection refusal only; automatic retries and redirects remain disabled. */
  static final HttpRetryPolicy.Factory PRE_SEND_RETRY =
      new HttpRetryPolicy.Factory(3, 100, 2.0) {
        @Override
        public HttpRetryPolicy create() {
          return new PreSendRetryPolicy();
        }
      };

  private final BackendApiFactory backendApiFactory;
  private final boolean agentless;
  private final boolean directFallbackAvailable;
  private final LongSupplier nanoTime;
  private volatile Clients clients;
  private boolean discovered;
  private long nextDiscoveryNanos;

  /**
   * @param backendApiFactory the send-once factory of event platform proxy clients.
   * @param agentless whether the configuration source is agentless, so events use the discovered
   *     proxy endpoint, or direct intake when the SDK can fall back to it.
   * @param directFallbackAvailable whether the SDK falls back to direct intake when the proxy is
   *     unavailable.
   */
  EventProxy(
      final BackendApiFactory backendApiFactory,
      final boolean agentless,
      final boolean directFallbackAvailable) {
    this(backendApiFactory, agentless, directFallbackAvailable, System::nanoTime);
  }

  EventProxy(
      final BackendApiFactory backendApiFactory,
      final boolean agentless,
      final boolean directFallbackAvailable,
      final LongSupplier nanoTime) {
    this.backendApiFactory = backendApiFactory;
    this.agentless = agentless;
    this.directFallbackAvailable = directFallbackAvailable;
    this.nanoTime = nanoTime;
  }

  /**
   * @return whether events go through the event platform proxy: always with the Remote
   *     Configuration source, and with the agentless source when the proxy endpoint is discovered
   *     or when direct intake is not available.
   */
  boolean isAvailable() {
    // Only the agentless source can lack proxy clients.
    return current() != null || !this.directFallbackAvailable;
  }

  /**
   * Posts a payload.
   *
   * @param route the event platform route.
   * @param json the UTF-8 JSON payload.
   * @return {@code true} if delivered, {@code false} if the proxy definitively did not receive the
   *     payload.
   * @throws IOException if the payload could not be delivered.
   */
  boolean post(final String route, final byte[] json) throws IOException {
    Clients current = current();
    if (current == null) {
      current = recover();
      if (current == null) {
        return false;
      }
    }
    final BackendApi api =
        EXPOSURES_ROUTE.equals(route) ? current.exposures : current.flagEvaluations;
    try {
      api.post(route, RequestBody.create(JSON, json), stream -> null, null, false);
      return true;
    } catch (final IOException e) {
      if (isDefinitiveRejection(e)) {
        onDefinitiveRejection(current);
        return false;
      }
      throw e;
    }
  }

  @Nullable
  private Clients current() {
    final Clients current = this.clients;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (!this.discovered) {
        this.discovered = true;
        this.clients =
            this.agentless ? discover(false) : clients(PRE_SEND_RETRY, V2_EVP_PROXY_ENDPOINT);
        if (this.clients == null) {
          onUnavailable();
        }
      }
      return this.clients;
    }
  }

  @Nullable
  private synchronized Clients recover() {
    if (this.clients != null) {
      return this.clients;
    }
    if (this.directFallbackAvailable || this.nanoTime.getAsLong() - this.nextDiscoveryNanos < 0) {
      return null;
    }
    this.clients = discover(true);
    if (this.clients == null) {
      scheduleDiscovery();
    } else {
      LOGGER.info("Feature Flags event delivery recovered through the Datadog Agent event proxy");
    }
    return this.clients;
  }

  private synchronized void onDefinitiveRejection(final Clients failed) {
    // Only the agentless proxy route without direct intake waits for a new endpoint. An in-flight
    // send on an old endpoint cannot invalidate a newly recovered one.
    if (this.agentless && !this.directFallbackAvailable && this.clients == failed) {
      this.clients = null;
      onUnavailable();
    }
  }

  /** Waits for a new proxy endpoint when it is the only route. Runs once per outage. */
  private void onUnavailable() {
    if (!this.directFallbackAvailable) {
      scheduleDiscovery();
      LOGGER.warn(
          "Feature Flags events are being dropped because no local EVP proxy or direct intake"
              + " route is available. Make the Agent reachable, or configure DD_API_KEY and a"
              + " valid DD_SITE.");
    }
  }

  private void scheduleDiscovery() {
    this.nextDiscoveryNanos = this.nanoTime.getAsLong() + RECOVERY_INTERVAL_NANOS;
  }

  @Nullable
  private Clients discover(final boolean force) {
    final String endpoint;
    try {
      endpoint = this.backendApiFactory.discoverEvpProxyEndpoint(force, emptyList());
    } catch (final RuntimeException e) {
      LOGGER.debug("Could not discover the Datadog Agent event proxy", e);
      return null;
    }
    return endpoint == null ? null : clients(NEVER_RETRY, endpoint);
  }

  private Clients clients(final HttpRetryPolicy.Factory retryPolicy, final String endpoint) {
    return new Clients(
        this.backendApiFactory.createEvpProxyApiForEndpoint(
            EVENT_PLATFORM, EXPOSURE.responseCompressionEnabled(), retryPolicy, endpoint),
        this.backendApiFactory.createEvpProxyApiForEndpoint(
            EVENT_PLATFORM, FLAG_EVALUATION.responseCompressionEnabled(), retryPolicy, endpoint));
  }

  /**
   * Tells whether the proxy definitively did not receive a payload, so it can be replayed through
   * direct intake.
   *
   * @param exception the post failure.
   * @return whether the failure is a connection refusal or a missing proxy route.
   */
  static boolean isDefinitiveRejection(final IOException exception) {
    if (exception instanceof ConnectException) {
      return true;
    }
    if (exception instanceof HttpResponseException) {
      final int statusCode = ((HttpResponseException) exception).getStatusCode();
      return statusCode == 404 || statusCode == 405;
    }
    return false;
  }

  /** The proxy clients of both routes, for one endpoint. */
  private static final class Clients {
    final BackendApi exposures;
    final BackendApi flagEvaluations;

    Clients(final BackendApi exposures, final BackendApi flagEvaluations) {
      this.exposures = exposures;
      this.flagEvaluations = flagEvaluations;
    }
  }

  private static final class PreSendRetryPolicy extends HttpRetryPolicy {
    private int attemptsLeft = 3;

    private PreSendRetryPolicy() {
      super(3, 100, 2.0, false);
    }

    @Override
    public boolean shouldRetry(final Exception exception) {
      if (exception instanceof ConnectException && this.attemptsLeft > 0) {
        this.attemptsLeft--;
        return true;
      }
      return false;
    }

    @Override
    public boolean shouldRetry(@Nullable final Response response) {
      return false;
    }
  }
}
