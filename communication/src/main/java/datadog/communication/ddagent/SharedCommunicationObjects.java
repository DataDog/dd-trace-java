package datadog.communication.ddagent;

import datadog.common.container.ContainerInfo;
import datadog.common.socket.SocketUtils;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
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
      log.error("Error creating remote configuration poller", e);
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
        confPollerCls.getConstructor(
            Config.class, String.class, String.class, Supplier.class, OkHttpClient.class);

    String containerId = ContainerInfo.get().getContainerId();
    String remoteConfigUrl = config.getFinalRemoteConfigUrl();
    Supplier<String> configUrlSupplier;
    if (remoteConfigUrl != null) {
      configUrlSupplier = new FixedConfigUrlSupplier(remoteConfigUrl);
    } else {
      createRemaining(config);
      configUrlSupplier = new RetryConfigUrlSupplier(this, config);
    }

    return constructor.newInstance(
        config, TracerVersion.TRACER_VERSION, containerId, configUrlSupplier, okHttpClient);
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
