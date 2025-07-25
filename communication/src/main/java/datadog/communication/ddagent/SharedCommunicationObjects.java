package datadog.communication.ddagent;

import static datadog.communication.ddagent.TracerVersion.TRACER_VERSION;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import datadog.common.container.ContainerInfo;
import datadog.common.socket.SocketUtils;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.monitor.Monitoring;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.DefaultConfigurationPoller;
import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SharedCommunicationObjects {
  private static final Logger log = LoggerFactory.getLogger(SharedCommunicationObjects.class);

  private final List<Runnable> pausedComponents = new ArrayList<>();
  private volatile boolean paused;

  public OkHttpClient okHttpClient;
  public HttpUrl agentUrl;
  public Monitoring monitoring;
  private volatile DDAgentFeaturesDiscovery featuresDiscovery;
  private ConfigurationPoller configurationPoller;

  public SharedCommunicationObjects() {
    this(false);
  }

  public SharedCommunicationObjects(boolean paused) {
    this.paused = paused;
  }

  public void createRemaining(Config config) {
    if (monitoring == null) {
      monitoring = Monitoring.DISABLED;
    }
    if (agentUrl == null) {
      agentUrl = parseAgentUrl(config);
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

  /** Registers a callback to be called when remote communications resume. */
  public void whenReady(Runnable callback) {
    if (paused) {
      synchronized (pausedComponents) {
        if (paused) {
          pausedComponents.add(callback);
          return;
        }
      }
    }
    callback.run(); // not paused, run immediately
  }

  /** Resumes remote communications including any paused callbacks. */
  public void resume() {
    paused = false;
    // attempt discovery first to avoid potential race condition on IBM Java8
    if (null != featuresDiscovery) {
      featuresDiscovery.discoverIfOutdated();
    } else {
      Security.getProviders(); // fallback to preloading provider extensions
    }
    synchronized (pausedComponents) {
      for (Runnable callback : pausedComponents) {
        try {
          callback.run();
        } catch (Throwable e) {
          log.warn("Problem resuming remote component {}", callback, e);
        }
      }
      pausedComponents.clear();
    }
  }

  private static HttpUrl parseAgentUrl(Config config) {
    String agentUrl = config.getAgentUrl();
    if (agentUrl.startsWith("unix:")) {
      // provide placeholder agent URL, in practice we'll be tunnelling over UDS
      agentUrl = "http://" + config.getAgentHost() + ":" + config.getAgentPort();
    }
    return HttpUrl.parse(agentUrl);
  }

  private static long getHttpClientTimeout(Config config) {
    if (!config.isCiVisibilityEnabled()) {
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
    return new DefaultConfigurationPoller(
        config, TRACER_VERSION, containerId, entityId, configUrlSupplier, okHttpClient);
  }

  // for testing
  public void setFeaturesDiscovery(DDAgentFeaturesDiscovery featuresDiscovery) {
    this.featuresDiscovery = featuresDiscovery;
  }

  public DDAgentFeaturesDiscovery featuresDiscovery(Config config) {
    DDAgentFeaturesDiscovery ret = featuresDiscovery;
    if (ret == null) {
      synchronized (this) {
        if (featuresDiscovery == null) {
          createRemaining(config);
          ret =
              new DDAgentFeaturesDiscovery(
                  okHttpClient,
                  monitoring,
                  agentUrl,
                  config.isTraceAgentV05Enabled(),
                  config.isTracerMetricsEnabled());

          if (paused) {
            // defer remote discovery until remote I/O is allowed
          } else {
            if (AGENT_THREAD_GROUP.equals(Thread.currentThread().getThreadGroup())) {
              ret.discover(); // safe to run on same thread
            } else {
              // avoid performing blocking I/O operation on application thread
              AgentTaskScheduler.INSTANCE.execute(ret::discoverIfOutdated);
            }
          }
          featuresDiscovery = ret;
        }
      }
    }
    return ret;
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
