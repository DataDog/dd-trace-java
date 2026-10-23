package com.datadog.profiling.remoteconfig;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableMap;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.ProfilingEnablement;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscribes to the {@code APM_PROFILING} remote-config product and hands the active configs to a
 * {@link ProfilingConfigHandler} after each commit.
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

  public void start(Config config, SharedCommunicationObjects sco) {
    log.debug("Starting profiling remote config");

    ConfigurationPoller poller = sco.configurationPoller(config);
    if (poller == null) {
      log.debug("Remote config is not enabled, not subscribing to APM_PROFILING");
      return;
    }
    // the poller itself is started by the agent once all products have been registered
    poller.addListener(Product.APM_PROFILING, new Updater());
    configPoller = poller;
    log.debug("Subscribed to APM_PROFILING remote config");
  }

  public void stop() {
    if (null != configPoller) {
      // only unsubscribe; the poller is shared and its start/stop calls are reference-counted
      configPoller.removeListeners(Product.APM_PROFILING);
      configPoller = null;
    }
  }

  /** Remote-config callbacks all come from the single poller thread. */
  final class Updater implements ProductListener {
    private final Map<String, String> configs = new HashMap<>();

    @Override
    public void accept(ConfigKey configKey, byte[] content, PollingRateHinter hinter) {
      String json = new String(content, UTF_8);
      configs.put(configKey.getConfigId(), json);
      log.debug("Received APM_PROFILING config {}: {}", configKey.getConfigId(), json);
    }

    @Override
    public void remove(ConfigKey configKey, PollingRateHinter hinter) {
      configs.remove(configKey.getConfigId());
      log.debug("Removed APM_PROFILING config {}", configKey.getConfigId());
    }

    @Override
    public void commit(PollingRateHinter hinter) {
      try {
        handler.onConfigsChanged(unmodifiableMap(new HashMap<>(configs)));
      } catch (Throwable t) {
        log.warn(SEND_TELEMETRY, "Failed to apply APM_PROFILING remote config", t);
      }
    }
  }
}
