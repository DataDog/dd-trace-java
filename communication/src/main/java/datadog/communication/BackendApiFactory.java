package datadog.communication;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.intake.AgentlessIntake;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
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

  public @Nullable BackendApi createBackendApi(AgentlessIntake intake) {
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0, true);

    if (intake.isAgentlessEnabled(config)) {
      HttpUrl agentlessUrl = HttpUrl.get(intake.getAgentlessUrl(config));
      String apiKey = config.getApiKey();
      if (apiKey == null || apiKey.isEmpty()) {
        throw new FatalAgentMisconfigurationError(
            "Agentless mode is enabled and api key is not set. Please set application key");
      }
      String traceId = config.getIdGenerationStrategy().generateTraceId().toString();
      OkHttpClient httpClient =
          OkHttpUtils.buildHttpClient(
              agentlessUrl, config.getCiVisibilityBackendApiTimeoutMillis());
      return new IntakeApi(agentlessUrl, apiKey, traceId, retryPolicyFactory, httpClient, true);
    }

    DDAgentFeaturesDiscovery featuresDiscovery =
        sharedCommunicationObjects.featuresDiscovery(config);
    featuresDiscovery.discoverIfOutdated();
    if (featuresDiscovery.supportsEvpProxy()) {
      String traceId = config.getIdGenerationStrategy().generateTraceId().toString();
      String evpProxyEndpoint = featuresDiscovery.getEvpProxyEndpoint();
      HttpUrl evpProxyUrl = sharedCommunicationObjects.agentUrl.resolve(evpProxyEndpoint);
      return new EvpProxyApi(
          traceId, evpProxyUrl, retryPolicyFactory, sharedCommunicationObjects.okHttpClient, true);
    }

    log.warn(
        "Cannot create backend API client since agentless mode is disabled, "
            + "and agent does not support EVP proxy");
    return null;
  }
}
