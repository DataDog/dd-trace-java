package datadog.communication.ddagent;

import datadog.common.socket.SocketUtils;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.Config;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class SharedCommunicationObjects {
  public OkHttpClient okHttpClient;
  public HttpUrl agentUrl;
  public Monitoring monitoring;
  public DDAgentFeaturesDiscovery featuresDiscovery;

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
    featuresDiscovery(config);
  }

  public DDAgentFeaturesDiscovery featuresDiscovery(Config config) {
    if (featuresDiscovery == null) {
      featuresDiscovery =
          new DDAgentFeaturesDiscovery(
              okHttpClient,
              monitoring,
              agentUrl,
              config.isTraceAgentV05Enabled(),
              config.isTracerMetricsEnabled());
    }
    return featuresDiscovery;
  }
}
