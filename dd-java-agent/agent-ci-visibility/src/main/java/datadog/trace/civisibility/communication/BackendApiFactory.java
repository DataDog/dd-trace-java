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
    long timeoutMillis = config.getCiVisibilityBackendApiTimeoutMillis();
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0);

    if (config.isCiVisibilityAgentlessEnabled()) {
      String site = config.getSite();
      String apiKey = config.getApiKey();
      if (apiKey == null || apiKey.isEmpty()) {
        throw new FatalAgentMisconfigurationError(
            "Agentless mode is enabled and api key is not set. Please set application key");
      }
      String applicationKey = config.getApplicationKey();
      if (applicationKey == null || applicationKey.isEmpty()) {
        log.warn(
            "Agentless mode is enabled and application key is not set. Some CI Visibility features will be unavailable");
      }
      return new IntakeApi(site, apiKey, applicationKey, timeoutMillis, retryPolicyFactory);
    }

    DDAgentFeaturesDiscovery featuresDiscovery =
        sharedCommunicationObjects.featuresDiscovery(config);
    if (featuresDiscovery.supportsEvpProxy()) {
      String evpProxyEndpoint = featuresDiscovery.getEvpProxyEndpoint();
      HttpUrl evpProxyUrl = sharedCommunicationObjects.agentUrl.resolve(evpProxyEndpoint);
      return new EvpProxyApi(evpProxyUrl, timeoutMillis, retryPolicyFactory);
    }

    log.warn(
        "Cannot create backend API client since agentless mode is disabled, "
            + "and agent does not support EVP proxy");
    return null;
  }
}
