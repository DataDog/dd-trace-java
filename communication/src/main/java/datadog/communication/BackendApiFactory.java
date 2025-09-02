package datadog.communication;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import java.util.function.Function;
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

  public @Nullable BackendApi createBackendApi(Intake intake) {
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0, true);

    if (intake.agentlessModeEnabled.apply(config)) {
      HttpUrl agentlessUrl = getAgentlessUrl(intake);
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

  private HttpUrl getAgentlessUrl(Intake intake) {
    String customUrl = intake.customUrl.apply(config);
    if (customUrl != null && !customUrl.isEmpty()) {
      return HttpUrl.get(String.format("%s/api/%s/", customUrl, intake.version));
    } else {
      String site = config.getSite();
      return HttpUrl.get(
          String.format("https://%s.%s/api/%s/", intake.urlPrefix, site, intake.version));
    }
  }

  public enum Intake {
    API("api", "v2", Config::isCiVisibilityAgentlessEnabled, Config::getCiVisibilityAgentlessUrl),
    LLMOBS_API("api", "v2", Config::isLlmObsAgentlessEnabled, Config::getLlMObsAgentlessUrl),
    LOGS(
        "http-intake.logs",
        "v2",
        Config::isAgentlessLogSubmissionEnabled,
        Config::getAgentlessLogSubmissionUrl),
    CI_INTAKE(
        "ci-intake",
        "v2",
        Config::isCiVisibilityAgentlessEnabled,
        Config::getCiVisibilityIntakeAgentlessUrl);

    public final String urlPrefix;
    public final String version;
    public final Function<Config, Boolean> agentlessModeEnabled;
    public final Function<Config, String> customUrl;

    Intake(
        String urlPrefix,
        String version,
        Function<Config, Boolean> agentlessModeEnabled,
        Function<Config, String> customUrl) {
      this.urlPrefix = urlPrefix;
      this.version = version;
      this.agentlessModeEnabled = agentlessModeEnabled;
      this.customUrl = customUrl;
    }
  }
}
