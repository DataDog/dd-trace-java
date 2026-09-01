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

  public BackendApiFactory(Config config, SharedCommunicationObjects sharedCommunicationObjects) {
    this(config, sharedCommunicationObjects, emptyMap());
  }

  public BackendApiFactory(
      Config config,
      SharedCommunicationObjects sharedCommunicationObjects,
      Map<String, String> requestHeaders) {
    this.config = config;
    this.sharedCommunicationObjects = sharedCommunicationObjects;
    this.requestHeaders = unmodifiableMap(new HashMap<>(requestHeaders));
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
    HttpUrl agentlessUrl = HttpUrl.get(intake.getAgentlessUrl(config));
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
        withRequestHeaders(sharedCommunicationObjects.getIntakeHttpClient()),
        responseCompression);
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
    DDAgentFeaturesDiscovery featuresDiscovery =
        sharedCommunicationObjects.featuresDiscovery(config);
    featuresDiscovery.discoverIfOutdated();
    if (!featuresDiscovery.supportsEvpProxy()) {
      return null;
    }
    String evpProxyEndpoint = featuresDiscovery.getEvpProxyEndpoint();

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
        retryPolicyFactory,
        withRequestHeaders(sharedCommunicationObjects.agentHttpClient),
        responseCompression);
  }

  private OkHttpClient withRequestHeaders(final OkHttpClient httpClient) {
    if (requestHeaders.isEmpty()) {
      return httpClient;
    }
    return httpClient
        .newBuilder()
        .addInterceptor(
            chain -> {
              final Request.Builder requestBuilder = chain.request().newBuilder();
              for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
              }
              return chain.proceed(requestBuilder.build());
            })
        .build();
  }

  private static HttpRetryPolicy.Factory retryPolicyFactory() {
    return new HttpRetryPolicy.Factory(5, 100, 2.0, true);
  }
}
