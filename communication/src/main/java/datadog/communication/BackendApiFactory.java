package datadog.communication;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackendApiFactory {

  private static final Logger log = LoggerFactory.getLogger(BackendApiFactory.class);

  private final Config config;
  private final SharedCommunicationObjects sharedCommunicationObjects;
  private final Map<String, String> requestHeaders;
  private final boolean sendOnce;

  public BackendApiFactory(Config config, SharedCommunicationObjects sharedCommunicationObjects) {
    this(config, sharedCommunicationObjects, emptyMap());
  }

  public BackendApiFactory(
      Config config,
      SharedCommunicationObjects sharedCommunicationObjects,
      Map<String, String> requestHeaders) {
    this(config, sharedCommunicationObjects, requestHeaders, false);
  }

  /**
   * Creates a backend factory with per-request headers and optional send-once transport semantics.
   *
   * <p>When {@code sendOnce} is true, both the explicit HTTP retry policy and OkHttp's automatic
   * connection retry are disabled. This is required for event payloads that do not carry an
   * idempotency key.
   */
  public BackendApiFactory(
      Config config,
      SharedCommunicationObjects sharedCommunicationObjects,
      Map<String, String> requestHeaders,
      boolean sendOnce) {
    this.config = config;
    this.sharedCommunicationObjects = sharedCommunicationObjects;
    this.requestHeaders = unmodifiableMap(new HashMap<>(requestHeaders));
    this.sendOnce = sendOnce;
  }

  public @Nullable BackendApi createBackendApi(Intake intake) {
    return createBackendApi(intake, true);
  }

  public @Nullable BackendApi createBackendApi(Intake intake, boolean responseCompression) {
    if (intake.isAgentlessEnabled(config)) {
      return createDirectIntakeApi(intake, responseCompression);
    }

    BackendApi backendApi = createEvpProxyApi(intake, responseCompression);
    if (backendApi == null) {
      log.warn(
          "Cannot create backend API client since agentless mode is disabled, "
              + "and agent does not support EVP proxy");
    }
    return backendApi;
  }

  /** Creates an authenticated API client that sends data directly to a Datadog intake. */
  public BackendApi createDirectIntakeApi(Intake intake) {
    return createDirectIntakeApi(intake, true);
  }

  /** Creates an authenticated API client that sends data directly to a Datadog intake. */
  public BackendApi createDirectIntakeApi(Intake intake, boolean responseCompression) {
    return createDirectIntakeApi(intake, responseCompression, true);
  }

  /** Creates an authenticated API client that sends data directly to a Datadog intake. */
  public BackendApi createDirectIntakeApi(
      Intake intake, boolean responseCompression, boolean followRedirects) {
    HttpUrl agentlessUrl = buildDirectIntakeUrl(intake, config);
    String apiKey = config.getApiKey();
    if (apiKey == null || apiKey.isEmpty()) {
      throw new FatalAgentMisconfigurationError(
          "Agentless mode is enabled and API key is not set. Please set DD_API_KEY");
    }
    String traceId = config.getIdGenerationStrategy().generateTraceId().toString();
    return new IntakeApi(
        agentlessUrl,
        apiKey,
        traceId,
        retryPolicyFactory(),
        configureHttpClient(
            directIntakeHttpClient(
                sharedCommunicationObjects.getIntakeHttpClient(), followRedirects)),
        responseCompression);
  }

  static OkHttpClient directIntakeHttpClient(
      final OkHttpClient intakeHttpClient, final boolean followRedirects) {
    if (followRedirects) {
      return intakeHttpClient;
    }
    return intakeHttpClient.newBuilder().followRedirects(false).build();
  }

  private static HttpUrl buildDirectIntakeUrl(Intake intake, Config config) {
    if (intake != Intake.EVENT_PLATFORM) {
      return HttpUrl.get(intake.getAgentlessUrl(config));
    }
    return buildEventPlatformIntakeUrl(config.getSite());
  }

  static HttpUrl buildEventPlatformIntakeUrl(String site) {
    if (site == null || site.isEmpty()) {
      throw new IllegalArgumentException("Invalid Datadog site");
    }

    String expectedHost = Intake.EVENT_PLATFORM.getUrlPrefix() + "." + site;
    HttpUrl url =
        new HttpUrl.Builder()
            .scheme("https")
            .host(expectedHost)
            .addPathSegment("api")
            .addPathSegment(Intake.EVENT_PLATFORM.getVersion())
            .addPathSegment("")
            .build();
    if (!url.host().equalsIgnoreCase(expectedHost)) {
      throw new IllegalArgumentException("Invalid Datadog site");
    }
    return url;
  }

  /** Creates an API client that uses the specified retry policy with a compatible local proxy. */
  public @Nullable BackendApi createEvpProxyApi(Intake intake) {
    return createEvpProxyApi(intake, true);
  }

  /** Creates an API client that sends data through a compatible local EVP proxy. */
  public @Nullable BackendApi createEvpProxyApi(Intake intake, boolean responseCompression) {
    return createEvpProxyApi(intake, responseCompression, retryPolicyFactory());
  }

  /** Creates an API client that sends data through a compatible local EVP proxy. */
  public @Nullable BackendApi createEvpProxyApi(
      Intake intake, boolean responseCompression, HttpRetryPolicy.Factory retryPolicyFactory) {
    return createEvpProxyApi(intake, responseCompression, retryPolicyFactory, null, false);
  }

  /**
   * Creates an EVP proxy client, optionally retaining a compatibility endpoint when Agent discovery
   * itself is unavailable.
   *
   * <p>An authoritative Agent response that omits EVP support never uses the fallback endpoint. The
   * {@code forceDiscovery} form is intended for bounded route-recovery probes.
   */
  public @Nullable BackendApi createEvpProxyApi(
      Intake intake,
      boolean responseCompression,
      HttpRetryPolicy.Factory retryPolicyFactory,
      @Nullable String discoveryFailureFallbackEndpoint,
      boolean forceDiscovery) {
    DDAgentFeaturesDiscovery featuresDiscovery =
        sharedCommunicationObjects.featuresDiscovery(config);
    if (forceDiscovery) {
      featuresDiscovery.discover();
    } else {
      featuresDiscovery.discoverIfOutdated();
    }
    String evpProxyEndpoint = featuresDiscovery.getEvpProxyEndpoint();
    if (evpProxyEndpoint == null
        && discoveryFailureFallbackEndpoint != null
        && !featuresDiscovery.hasValidInfoResponse()) {
      evpProxyEndpoint = discoveryFailureFallbackEndpoint;
    }
    if (evpProxyEndpoint == null) {
      return null;
    }

    String traceId = config.getIdGenerationStrategy().generateTraceId().toString();
    log.debug(
        "Creating EVP proxy client for {} using endpoint {} with responseCompression={}",
        intake,
        evpProxyEndpoint,
        responseCompression);
    HttpUrl evpProxyUrl = sharedCommunicationObjects.agentUrl.resolve(evpProxyEndpoint);
    String subdomain = intake.getUrlPrefix();
    return new EvpProxyApi(
        traceId,
        evpProxyUrl,
        subdomain,
        sendOnce ? HttpRetryPolicy.Factory.NEVER_RETRY : retryPolicyFactory,
        configureHttpClient(sharedCommunicationObjects.agentHttpClient),
        responseCompression);
  }

  OkHttpClient configureHttpClient(final OkHttpClient httpClient) {
    if (requestHeaders.isEmpty() && !sendOnce) {
      return httpClient;
    }
    final OkHttpClient.Builder builder = httpClient.newBuilder();
    if (sendOnce) {
      builder.retryOnConnectionFailure(false);
    }
    if (!requestHeaders.isEmpty()) {
      builder.addInterceptor(
          chain -> {
            final Request.Builder requestBuilder = chain.request().newBuilder();
            for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
              requestBuilder.header(header.getKey(), header.getValue());
            }
            return chain.proceed(requestBuilder.build());
          });
    }
    return builder.build();
  }

  private HttpRetryPolicy.Factory retryPolicyFactory() {
    return sendOnce
        ? HttpRetryPolicy.Factory.NEVER_RETRY
        : new HttpRetryPolicy.Factory(5, 100, 2.0, true);
  }
}
