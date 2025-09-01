package datadog.trace.api.intake;

import datadog.trace.api.Config;
import java.util.function.Function;

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

  public String getUrlPrefix() {
    return urlPrefix;
  }

  public String getVersion() {
    return version;
  }

  public boolean isAgentlessEnabled(Config config) {
    return agentlessModeEnabled.apply(config);
  }

  public String getAgentlessUrl(Config config) {
    String custom = customUrl.apply(config);
    if (custom != null && !custom.isEmpty()) {
      return String.format("%s/api/%s/", custom, version);
    } else {
      String site = config.getSite();
      return String.format("https://%s.%s/api/%s/", urlPrefix, site, version);
    }
  }
}
