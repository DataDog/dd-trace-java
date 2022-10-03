package datadog.communication.ddagent;

import datadog.common.container.ContainerInfo;
import datadog.common.socket.SocketUtils;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.function.Supplier;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;
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
      DDAgentFeaturesDiscovery fd = featuresDiscovery(config);
      String configEndpoint = fd.getConfigEndpoint();
      if (configEndpoint != null) {
        remoteConfigUrl = featuresDiscovery.buildUrl(configEndpoint).toString();
        configUrlSupplier = new FixedConfigUrlSupplier(remoteConfigUrl);
      } else {
        configUrlSupplier = new RetryConfigUrlSupplier(this.featuresDiscovery);
      }
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
    private final DDAgentFeaturesDiscovery featuresDiscovery;
    private String configUrl;
    private long lastTry = System.currentTimeMillis();
    private long retryInterval = 5000;

    private RetryConfigUrlSupplier(DDAgentFeaturesDiscovery featuresDiscovery) {
      this.featuresDiscovery = featuresDiscovery;
    }

    @Override
    public String get() {
      if (configUrl != null) {
        return configUrl;
      }
      long now = System.currentTimeMillis();
      long elapsed = now - lastTry;
      if (elapsed > retryInterval) {
        this.featuresDiscovery.discover();
        retryInterval = 60000;
      } else {
        return null;
      }
      lastTry = now;
      String configEndpoint = this.featuresDiscovery.getConfigEndpoint();
      if (configEndpoint == null) {
        return null;
      }

      this.configUrl = featuresDiscovery.buildUrl(configEndpoint).toString();
      return this.configUrl;
    }
  }
}
