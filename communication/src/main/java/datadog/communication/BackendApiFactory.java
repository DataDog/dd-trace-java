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
    return createBackendApi(intake, null, true);
  }

  public @Nullable BackendApi createBackendApi(
      Intake intake, @Nullable String preferredEvpProxyEndpoint, boolean responseCompression) {
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0, true);

    if (intake.isAgentlessEnabled(config)) {
      HttpUrl agentlessUrl = HttpUrl.get(intake.getAgentlessUrl(config));
      String apiKey = config.getApiKey();
      if (apiKey == null || apiKey.isEmpty()) {
        throw new FatalAgentMisconfigurationError(
            "Agentless mode is enabled and api key is not set. Please set application key");
      }
      String traceId = config.getIdGenerationStrategy().generateTraceId().toString();
      return new IntakeApi(
          agentlessUrl,
          apiKey,
          traceId,
          retryPolicyFactory,
          sharedCommunicationObjects.getIntakeHttpClient(),
          true);
    }

    DDAgentFeaturesDiscovery featuresDiscovery =
        sharedCommunicationObjects.featuresDiscovery(config);
    featuresDiscovery.discoverIfOutdated();
    String evpProxyEndpoint;
    if (preferredEvpProxyEndpoint != null) {
      if (!featuresDiscovery.supportsEvpProxyEndpoint(preferredEvpProxyEndpoint)) {
        log.warn(
            "Cannot create backend API client for {} since agent does not support requested EVP"
                + " proxy endpoint {}",
            intake,
            preferredEvpProxyEndpoint);
        return null;
      }
      evpProxyEndpoint = preferredEvpProxyEndpoint;
    } else if (featuresDiscovery.supportsEvpProxy()) {
      evpProxyEndpoint = featuresDiscovery.getEvpProxyEndpoint();
    } else {
      log.warn(
          "Cannot create backend API client since agentless mode is disabled, "
              + "and agent does not support EVP proxy");
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
        retryPolicyFactory,
        sharedCommunicationObjects.agentHttpClient,
        responseCompression);
  }
}
