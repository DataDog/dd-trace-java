package com.datadog.profiling.remoteconfig;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.ProfilingEnablement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscribes to the {@code APM_PROFILING} remote-config product, stores the accepted configs in a
 * {@link RemoteConfigurationStack} and hands it to a {@link ProfilingConfigHandler} after each
 * commit.
 */
public final class ProfilingConfigPoller {
  private static final Logger log = LoggerFactory.getLogger(ProfilingConfigPoller.class);

  private final ProfilingConfigHandler handler;

  private ConfigurationPoller configPoller;

  public ProfilingConfigPoller(ProfilingConfigHandler handler) {
    this.handler = handler;
  }

  /** Profiling remote config is only active when profiling is enabled with {@code armed}. */
  public static boolean isEnabled(Config config) {
    return config.getProfilingEnablement() == ProfilingEnablement.ARMED;
  }

  /**
   * Subscribes to the {@code APM_PROFILING} product.
   *
   * @return whether it subscribed, which it doesn't when remote config is not enabled
   */
  public boolean start(Config config, SharedCommunicationObjects sco) {
    log.debug("Starting profiling remote config");

    ConfigurationPoller poller = sco.configurationPoller(config);
    if (poller == null) {
      log.debug("Remote config is not enabled, not subscribing to APM_PROFILING");
      return false;
    }
    // the poller itself is started by the agent once all products have been registered
    poller.addListener(
        Product.APM_PROFILING, new Updater(config.getEnv(), config.getServiceName()));
    configPoller = poller;
    log.debug("Subscribed to APM_PROFILING remote config");
    return true;
  }

  public void stop() {
    if (null != configPoller) {
      // only unsubscribe; the poller is shared and its start/stop calls are reference-counted
      configPoller.removeListeners(Product.APM_PROFILING);
      configPoller = null;
    }
    log.debug("Stopped APM_PROFILING remote config");
  }

  /**
   * Keeps the accepted configs in a priority stack. Remote-config callbacks all come from the
   * single poller thread.
   */
  final class Updater implements ProductListener {
    private final RemoteConfigurationStack stack = new RemoteConfigurationStack();
    private final String env;
    private final String service;

    Updater(String env, String service) {
      this.env = env;
      this.service = service;
    }

    @Override
    public void accept(ConfigKey configKey, byte[] content, PollingRateHinter hinter) {
      String configId = configKey.getConfigId();
      String json = new String(content, UTF_8);
      // the more specific the targets are for this application, the higher the priority
      int priority = ProfilingConfigTargets.rank(json, env, service);
      if (priority == ProfilingConfigTargets.NO_MATCH) {
        // may have targeted this application before, in which case it no longer applies
        stack.remove(configId);
        log.debug(
            "Ignoring APM_PROFILING config {}: not targeting env={} service={}",
            configId,
            env,
            service);
        return;
      }
      RemoteConfiguration configuration = RemoteConfiguration.parse(json);
      stack.push(configId, priority, configuration);
      log.debug(
          "Stored APM_PROFILING config {} in the priority stack with priority {}: {}",
          configId,
          priority,
          configuration);
    }

    @Override
    public void remove(ConfigKey configKey, PollingRateHinter hinter) {
      stack.remove(configKey.getConfigId());
      log.debug("Removed APM_PROFILING config {} from the priority stack", configKey.getConfigId());
    }

    @Override
    public void commit(PollingRateHinter hinter) {
      try {
        handler.onConfigsChanged(stack.snapshot());
      } catch (Throwable t) {
        log.warn(SEND_TELEMETRY, "Failed to apply APM_PROFILING remote config", t);
      }
    }
  }
}
