package datadog.trace.civisibility.communication;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.Config;
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

  public @Nullable BackendApi createBackendApi() {
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0);

    if (config.isCiVisibilityAgentlessEnabled()) {
      HttpUrl agentlessUrl = getAgentlessUrl();
      String apiKey = config.getApiKey();
      if (apiKey == null || apiKey.isEmpty()) {
        throw new FatalAgentMisconfigurationError(
            "Agentless mode is enabled and api key is not set. Please set application key");
      }
      String traceId = config.getIdGenerationStrategy().generateTraceId().toString();
      long timeoutMillis = config.getCiVisibilityBackendApiTimeoutMillis();
      return new IntakeApi(agentlessUrl, apiKey, traceId, timeoutMillis, retryPolicyFactory);
    }

    DDAgentFeaturesDiscovery featuresDiscovery =
        sharedCommunicationObjects.featuresDiscovery(config);
    featuresDiscovery.discoverIfOutdated();
    if (featuresDiscovery.supportsEvpProxy()) {
      String traceId = config.getIdGenerationStrategy().generateTraceId().toString();
      String evpProxyEndpoint = featuresDiscovery.getEvpProxyEndpoint();
      HttpUrl evpProxyUrl = sharedCommunicationObjects.agentUrl.resolve(evpProxyEndpoint);
      return new EvpProxyApi(
          traceId, evpProxyUrl, retryPolicyFactory, sharedCommunicationObjects.okHttpClient);
    }

    log.warn(
        "Cannot create backend API client since agentless mode is disabled, "
            + "and agent does not support EVP proxy");
    return null;
  }

  private HttpUrl getAgentlessUrl() {
    final String ciVisibilityAgentlessUrlStr = config.getCiVisibilityAgentlessUrl();
    if (ciVisibilityAgentlessUrlStr != null && !ciVisibilityAgentlessUrlStr.isEmpty()) {
      return HttpUrl.get(
          String.format("%s/api/%s/", ciVisibilityAgentlessUrlStr, IntakeApi.API_VERSION));
    } else {
      String site = config.getSite();
      return HttpUrl.get(String.format("https://api.%s/api/%s/", site, IntakeApi.API_VERSION));
    }
  }
}
