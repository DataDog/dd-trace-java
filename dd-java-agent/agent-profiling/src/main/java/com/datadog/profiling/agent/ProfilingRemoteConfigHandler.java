package com.datadog.profiling.agent;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.ddprof.DatadogProfiler;
import com.datadog.profiling.remoteconfig.ProfilingConfigHandler;
import com.datadog.profiling.remoteconfig.ProfilingConfigMerger;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts, restarts or stops the profiler according to the active {@code DEBUG} configs. Remote
 * values take precedence over the local configuration; when no remote config is active the local
 * configuration applies again.
 *
 * <p>This handler can be created before the profiler is initialized. Configs received before the
 * profiler is available are buffered and applied once {@link #setProfilingSystem} is called.
 */
final class ProfilingRemoteConfigHandler implements ProfilingConfigHandler {
  private static final Logger log = LoggerFactory.getLogger(ProfilingRemoteConfigHandler.class);

  private volatile ProfilingSystem profilingSystem;
  private final ConfigProvider localConfig;
  private final boolean runWithLocalConfig;
  private final Function<Map<String, String>, ConfigProvider> remoteConfigFactory;
  private final Predicate<ConfigProvider> anyProfilingEnabled;

  // the remote values currently applied, null when the local configuration applies
  private Map<String, String> appliedValues;
  // configs received before the profiler was available
  private Map<String, String> pendingConfigsById;
  private String pendingConfigId;
  private boolean closed;

  /**
   * Creates a handler without a profiling system. The profiler must be set later via {@link
   * #setProfilingSystem}.
   *
   * @param runWithLocalConfig whether the profiler should run when no remote config is active
   */
  ProfilingRemoteConfigHandler(ConfigProvider localConfig, boolean runWithLocalConfig) {
    this(
        null,
        localConfig,
        runWithLocalConfig,
        ConfigProvider::withRemoteOverride,
        DatadogProfiler::isAnyProfilingModeEnabled);
  }

  /**
   * @param runWithLocalConfig whether the profiler should run when no remote config is active
   * @deprecated Use {@link #ProfilingRemoteConfigHandler(ConfigProvider, boolean)} and {@link
   *     #setProfilingSystem} instead
   */
  @Deprecated
  ProfilingRemoteConfigHandler(
      ProfilingSystem profilingSystem, ConfigProvider localConfig, boolean runWithLocalConfig) {
    this(
        profilingSystem,
        localConfig,
        runWithLocalConfig,
        ConfigProvider::withRemoteOverride,
        DatadogProfiler::isAnyProfilingModeEnabled);
  }

  ProfilingRemoteConfigHandler(
      ProfilingSystem profilingSystem,
      ConfigProvider localConfig,
      boolean runWithLocalConfig,
      Function<Map<String, String>, ConfigProvider> remoteConfigFactory,
      Predicate<ConfigProvider> anyProfilingEnabled) {
    this.profilingSystem = profilingSystem;
    this.localConfig = localConfig;
    this.runWithLocalConfig = runWithLocalConfig;
    this.remoteConfigFactory = remoteConfigFactory;
    this.anyProfilingEnabled = anyProfilingEnabled;
  }

  /**
   * Sets the profiling system. If configs were received before this call, they are applied
   * immediately.
   */
  synchronized void setProfilingSystem(ProfilingSystem profilingSystem) {
    this.profilingSystem = profilingSystem;
    if (pendingConfigsById != null) {
      log.debug("Applying buffered DEBUG remote config");
      Map<String, String> pending = pendingConfigsById;
      String pendingId = pendingConfigId;
      pendingConfigsById = null;
      pendingConfigId = null;
      applyConfigs(pendingId, pending);
    }
  }

  @Override
  public synchronized void onConfigsChanged(String configId, Map<String, String> configsById) {
    if (closed) {
      log.debug("Profiling is shut down, ignoring DEBUG remote config");
      return;
    }
    if (profilingSystem == null) {
      log.debug("Profiler not yet initialized, buffering DEBUG remote config");
      pendingConfigsById = configsById;
      return;
    }
    applyConfigs(configId, configsById);
  }

  /** Applies the given configs to the profiling system. Must be called with the lock held. */
  private void applyConfigs(String configId, Map<String, String> configsById) {
    log.debug("DEBUG remote config received: " + configId);
    final Map<String, String> values =
        configsById.isEmpty() ? null : ProfilingConfigMerger.merge(configsById);
    if (Objects.equals(values, appliedValues)) {
      log.debug("DEBUG remote config unchanged, nothing to do");
      return;
    }
    final ConfigProvider config;
    final boolean enabled;
    if (values == null) {
      log.debug("No DEBUG remote config active, reverting to the local configuration");
      config = localConfig;
      enabled = runWithLocalConfig;
    } else {
      log.debug("Applying DEBUG remote config: {}", values);
      config = remoteConfigFactory.apply(values);
      enabled = anyProfilingEnabled.test(config);
    }

    final boolean running = profilingSystem.isRunning();
    try {
      if (!enabled) {
        if (running) {
          log.debug("Profiling lifecycle: running -> stopped (all profiling disabled)");
          profilingSystem.stop(false);
        } else {
          log.debug("Profiling lifecycle: idle -> idle (all profiling disabled)");
        }
      } else {
        if (running) {
          log.debug("Profiling lifecycle: running -> stopped (restarting with new configuration)");
          profilingSystem.stop(false);
        }
        profilingSystem.reconfigure(config);
        profilingSystem.start(true);
        log.debug(
            "Profiling lifecycle: {} -> running ({} configuration)",
            running ? "stopped" : "idle",
            values == null ? "local" : "remote");
      }
      appliedValues = values;
    } catch (UnsupportedEnvironmentException | RuntimeException e) {
      log.warn(SEND_TELEMETRY, "Failed to apply DEBUG remote config", e);
    }
  }

  /** Stops reacting to remote config, e.g. once profiling is shut down. */
  synchronized void close() {
    closed = true;
  }
}
