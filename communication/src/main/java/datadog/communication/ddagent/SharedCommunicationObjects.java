package datadog.communication.ddagent;

import datadog.common.socket.SocketUtils;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.monitor.Monitoring;
import datadog.remote_config.ConfigurationPoller;
import datadog.trace.api.Config;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class SharedCommunicationObjects {
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
    }
    if (okHttpClient == null) {
      String unixDomainSocket = SocketUtils.discoverApmSocket(config);
      String namedPipe = config.getAgentNamedPipe();
      okHttpClient =
          OkHttpUtils.buildHttpClient(
              agentUrl,
              unixDomainSocket,
              namedPipe,
              TimeUnit.SECONDS.toMillis(config.getAgentTimeout()));
    }
  }

  public ConfigurationPoller configurationPoller(Config config) {
    if (configurationPoller != null) {
      return configurationPoller;
    }

    if (config.isRemoteConfigEnabled()) {
      String remoteConfigUrl = config.getFinalRemoteConfigUrl();
      if (remoteConfigUrl != null) {
        configurationPoller =
            new ConfigurationPoller(
                config, TracerVersion.TRACER_VERSION, remoteConfigUrl, okHttpClient);
      } else {
        createRemaining(config);
        DDAgentFeaturesDiscovery fd = featuresDiscovery(config);
        String configEndpoint = fd.getConfigEndpoint();
        if (configEndpoint != null) {
          remoteConfigUrl = featuresDiscovery.buildUrl(configEndpoint).toString();
          configurationPoller =
              new ConfigurationPoller(
                  config, TracerVersion.TRACER_VERSION, remoteConfigUrl, okHttpClient);
        }
      }
    }

    return configurationPoller;
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
      featuresDiscovery.discover();
    }
    return featuresDiscovery;
  }
}
