package datadog.communication.ddagent;

import static datadog.communication.ddagent.TracerVersion.TRACER_VERSION;

import datadog.common.container.ContainerInfo;
import datadog.common.socket.SocketUtils;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.monitor.Monitoring;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.trace.api.Config;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SharedCommunicationObjects {
  private static final Logger log = LoggerFactory.getLogger(SharedCommunicationObjects.class);

  public OkHttpClient okHttpClient;
  public HttpUrl agentUrl;
  public Monitoring monitoring;
  private DDAgentFeaturesDiscovery featuresDiscovery;
  private ConfigurationPoller configurationPoller;

  public void createRemaining(Config config) {
    if (monitoring == null) {
      monitoring = Monitoring.DISABLED;
    }
    if (agentUrl == null) {
      agentUrl = HttpUrl.parse(config.getAgentUrl());
      if (agentUrl == null) {
        throw new IllegalArgumentException("Bad agent URL: " + config.getAgentUrl());
      }
    }
    if (okHttpClient == null) {
      String unixDomainSocket = SocketUtils.discoverApmSocket(config);
      String namedPipe = config.getAgentNamedPipe();
      okHttpClient =
          OkHttpUtils.buildHttpClient(
              agentUrl, unixDomainSocket, namedPipe, getHttpClientTimeout(config));
    }
  }

  private static long getHttpClientTimeout(Config config) {
    if (!config.isCiVisibilityEnabled() || !config.isCiVisibilityAgentlessEnabled()) {
      return TimeUnit.SECONDS.toMillis(config.getAgentTimeout());
    } else {
      return config.getCiVisibilityBackendApiTimeoutMillis();
    }
  }

  public ConfigurationPoller configurationPoller(Config config) {
    if (configurationPoller == null && config.isRemoteConfigEnabled()) {
      configurationPoller = createPoller(config);
    }
    return configurationPoller;
  }

  private ConfigurationPoller createPoller(Config config) {
    String containerId = ContainerInfo.get().getContainerId();
    String entityId = ContainerInfo.getEntityId();
    Supplier<String> configUrlSupplier;
    String remoteConfigUrl = config.getFinalRemoteConfigUrl();
    if (remoteConfigUrl != null) {
      configUrlSupplier = new FixedConfigUrlSupplier(remoteConfigUrl);
    } else {
      createRemaining(config);
      configUrlSupplier = new RetryConfigUrlSupplier(this, config);
    }
    return new ConfigurationPoller(
        config, TRACER_VERSION, containerId, entityId, configUrlSupplier, okHttpClient);
  }

  // for testing
  public void setFeaturesDiscovery(DDAgentFeaturesDiscovery featuresDiscovery) {
    this.featuresDiscovery = featuresDiscovery;
  }

  public DDAgentFeaturesDiscovery featuresDiscovery(Config config) {
    if (featuresDiscovery == null) {
      createRemaining(config);
      featuresDiscovery =
          new DDAgentFeaturesDiscovery(
              okHttpClient,
              monitoring,
              agentUrl,
              config.isTraceAgentV05Enabled(),
              config.isTracerMetricsEnabled());
      if (!"true".equalsIgnoreCase(System.getProperty("dd.test.no.early.discovery"))) {
        featuresDiscovery.discover();
      }
    }
    return featuresDiscovery;
  }

  private static final class FixedConfigUrlSupplier implements Supplier<String> {
    private final String configUrl;

    private FixedConfigUrlSupplier(String configUrl) {
      this.configUrl = configUrl;
    }

    @Override
    public String get() {
      return this.configUrl;
    }
  }

  private static final class RetryConfigUrlSupplier implements Supplier<String> {
    private String configUrl;
    private final SharedCommunicationObjects sco;
    private final Config config;

    private RetryConfigUrlSupplier(final SharedCommunicationObjects sco, final Config config) {
      this.sco = sco;
      this.config = config;
    }

    @Override
    public String get() {
      if (configUrl != null) {
        return configUrl;
      }

      final DDAgentFeaturesDiscovery discovery = sco.featuresDiscovery(config);
      discovery.discoverIfOutdated();
      final String configEndpoint = discovery.getConfigEndpoint();
      if (configEndpoint == null) {
        return null;
      }
      this.configUrl = discovery.buildUrl(configEndpoint).toString();
      log.debug("Found remote config endpoint: {}", this.configUrl);
      return this.configUrl;
    }
  }
}
