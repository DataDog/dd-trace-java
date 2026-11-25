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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  public ProfilingConfigPoller(ProfilingConfigHandler handler) {
    this.handler = handler;
  }

  /** Profiling remote config is only active when profiling is enabled with {@code armed}. */
  public static boolean isEnabled(Config config) {
    return config.getProfilingEnablement() == ProfilingEnablement.ARMED;
  }

  /**
   * Subscribes to the {@code APM_PROFILING} product, for good: at shutdown, the handler is closed
   * instead, since unsubscribing would remove every listener of the shared product.
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
    poller.addListener(Product.APM_PROFILING, new Updater());
    log.debug("Subscribed to APM_PROFILING remote config");
    return true;
  }

  /**
   * Keeps the accepted configs in a priority stack. Remote-config callbacks all come from the
   * single poller thread.
   */
  final class Updater implements ProductListener {
    private final RemoteConfigurationStack stack = new RemoteConfigurationStack();

    @Override
    public void accept(ConfigKey configKey, byte[] content, PollingRateHinter hinter)
        throws IOException {
      String configId = configKey.getConfigId();
      List<String> problems = new ArrayList<>();
      Map<String, Object> payload = ApmProfilingPayload.read(new String(content, UTF_8), problems);
      // null for a payload that is not a profiling config, as the product is shared, or is
      // malformed
      RemoteConfiguration configuration =
          payload == null ? null : RemoteConfiguration.parse(payload, problems);
      // the more specific the targets are, the higher the priority
      int priority =
          configuration == null
              ? ProfilingConfigTargets.DROPPED
              : ProfilingConfigTargets.rank(payload, problems);
      if (priority == ProfilingConfigTargets.DROPPED) {
        // may have been a usable config before, in which case it no longer applies
        stack.remove(configKey);
        log.debug("Ignoring APM_PROFILING config {}: {}", configId, problems);
      } else {
        stack.push(configKey, priority, configuration);
        log.debug(
            "Stored APM_PROFILING config {} in the priority stack with priority {}: {}",
            configId,
            priority,
            configuration);
      }
      if (!problems.isEmpty()) {
        // The config is stored or dropped above before throwing. The poller records the message as
        // the apply error of this config, reported to the backend, and still commits, so the valid
        // fields of a stored config apply. The message says which of the two happened.
        throw new IOException(
            "APM_PROFILING config "
                + configId
                + (priority == ProfilingConfigTargets.DROPPED
                    ? " ignored: "
                    : " applied without its invalid parts: ")
                + String.join("; ", problems));
      }
    }

    @Override
    public void remove(ConfigKey configKey, PollingRateHinter hinter) {
      // by its key, not its config id: a target replaced by another one with the same config id
      // is removed after the new one was accepted
      stack.remove(configKey);
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
