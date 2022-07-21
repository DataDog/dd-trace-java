package datadog.communication.ddagent;

import datadog.common.socket.SocketUtils;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class SharedCommunicationObjects {
  public OkHttpClient okHttpClient;
  public HttpUrl agentUrl;
  public Monitoring monitoring;
  private DDAgentFeaturesDiscovery featuresDiscovery;
  private Object configurationPoller; // java 8

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

  public Object configurationPoller(Config config) {
    if (configurationPoller != null) {
      return configurationPoller;
    }
    if (!isAtLeastJava8()) {
      return null;
    }

    try {
      this.configurationPoller = maybeCreatePoller(config);
    } catch (ClassNotFoundException
        | NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException e) {
      return null;
    }

    return configurationPoller;
  }

  private Object maybeCreatePoller(Config config)
      throws ClassNotFoundException, NoSuchMethodException, InstantiationException,
          IllegalAccessException, InvocationTargetException {
    if (!config.isRemoteConfigEnabled()) {
      return null;
    }

    Class<?> confPollerCls =
        getClass().getClassLoader().loadClass("datadog.remoteconfig.ConfigurationPoller");
    Constructor<?> constructor =
        confPollerCls.getConstructor(Config.class, String.class, String.class, OkHttpClient.class);

    String remoteConfigUrl = config.getFinalRemoteConfigUrl();
    if (remoteConfigUrl != null) {
      return constructor.newInstance(
          config, TracerVersion.TRACER_VERSION, remoteConfigUrl, okHttpClient);
    } else {
      createRemaining(config);
      DDAgentFeaturesDiscovery fd = featuresDiscovery(config);
      String configEndpoint = fd.getConfigEndpoint();
      if (configEndpoint != null) {
        remoteConfigUrl = featuresDiscovery.buildUrl(configEndpoint).toString();
        return constructor.newInstance(
            config, TracerVersion.TRACER_VERSION, remoteConfigUrl, okHttpClient);
      }
    }
    return null;
  }

  private static boolean isAtLeastJava8() {
    return Platform.isJavaVersionAtLeast(8, 0);
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
