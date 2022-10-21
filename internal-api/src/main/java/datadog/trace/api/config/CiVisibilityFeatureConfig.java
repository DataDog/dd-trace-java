package datadog.trace.api.config;

import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.DEFAULT_CIVISIBILITY_ENABLED;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilityFeatureConfig extends AbstractFeatureConfig {
  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilityConfig.class);
  private final boolean ciVisibilityEnabled;
  private final boolean ciVisibilityAgentlessEnabled;
  private final String ciVisibilityAgentlessUrl;

  public CiVisibilityFeatureConfig(ConfigProvider configProvider) {
    super(configProvider);
    this.ciVisibilityEnabled =
        configProvider.getBoolean(CIVISIBILITY_ENABLED, DEFAULT_CIVISIBILITY_ENABLED);
    this.ciVisibilityAgentlessEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_AGENTLESS_ENABLED, DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED);
    this.ciVisibilityAgentlessUrl = parseAgentlessUrl(configProvider);
  }

  private String parseAgentlessUrl(ConfigProvider configProvider) {
    final String ciVisibilityAgentlessUrlStr = configProvider.getString(CIVISIBILITY_AGENTLESS_URL);
    URI parsedCiVisibilityUri = null;
    if (ciVisibilityAgentlessUrlStr != null && !ciVisibilityAgentlessUrlStr.isEmpty()) {
      try {
        parsedCiVisibilityUri = new URL(ciVisibilityAgentlessUrlStr).toURI();
      } catch (MalformedURLException | URISyntaxException ex) {
        LOGGER.error(
            "Cannot parse CI Visibility agentless URL '{}', skipping", ciVisibilityAgentlessUrlStr);
      }
    }
    if (parsedCiVisibilityUri != null) {
      return ciVisibilityAgentlessUrlStr;
    } else {
      return null;
    }
  }

  public boolean isCiVisibilityEnabled() {
    return this.ciVisibilityEnabled;
  }

  public boolean isCiVisibilityAgentlessEnabled() {
    return this.ciVisibilityAgentlessEnabled;
  }

  public String getCiVisibilityAgentlessUrl() {
    return this.ciVisibilityAgentlessUrl;
  }

  @Override
  public String toString() {
    return "CiVisibilityFeatureConfig{"
        + "ciVisibilityEnabled="
        + this.ciVisibilityEnabled
        + ", ciVisibilityAgentlessEnabled="
        + this.ciVisibilityAgentlessEnabled
        + ", ciVisibilityAgentlessUrl='"
        + this.ciVisibilityAgentlessUrl
        + '\''
        + '}';
  }
}
