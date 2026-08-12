package datadog.communication;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackendApiFactory {

  private static final Logger log = LoggerFactory.getLogger(BackendApiFactory.class);

  private final Config config;
  private final SharedCommunicationObjects sharedCommunicationObjects;

  public BackendApiFactory(Config config, SharedCommunicationObjects sharedCommunicationObjects) {
    this.config = config;
    this.sharedCommunicationObjects = sharedCommunicationObjects;
  }

  public @Nullable BackendApi createBackendApi(Intake intake) {
    if (intake.isAgentlessEnabled(config)) {
      return createDirectIntakeApi(intake);
    }

    BackendApi backendApi = createEvpProxyApi(intake);
    if (backendApi == null) {
      log.warn(
          "Cannot create backend API client since agentless mode is disabled, "
              + "and agent does not support EVP proxy");
    }
    return backendApi;
  }

  /** Creates an authenticated API client that sends data directly to a Datadog intake. */
  public BackendApi createDirectIntakeApi(Intake intake) {
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
        sharedCommunicationObjects.getIntakeHttpClient(),
        true);
  }

  /** Creates an API client that sends data through a compatible local EVP proxy. */
  public @Nullable BackendApi createEvpProxyApi(Intake intake) {
    DDAgentFeaturesDiscovery featuresDiscovery =
        sharedCommunicationObjects.featuresDiscovery(config);
    featuresDiscovery.discoverIfOutdated();
    if (featuresDiscovery.supportsEvpProxy()) {
      String traceId = config.getIdGenerationStrategy().generateTraceId().toString();
      String evpProxyEndpoint = featuresDiscovery.getEvpProxyEndpoint();
      HttpUrl evpProxyUrl = sharedCommunicationObjects.agentUrl.resolve(evpProxyEndpoint);
      String subdomain = intake.getUrlPrefix();
      return new EvpProxyApi(
          traceId,
          evpProxyUrl,
          subdomain,
          retryPolicyFactory(),
          sharedCommunicationObjects.agentHttpClient,
          true);
    }
    return null;
  }

  private static HttpRetryPolicy.Factory retryPolicyFactory() {
    return new HttpRetryPolicy.Factory(5, 100, 2.0, true);
  }
}
