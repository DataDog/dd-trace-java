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
 * profiler is available via {@link #setProfilingSystem} are dropped, not buffered.
 */
final class ProfilingRemoteConfigHandler implements ProfilingConfigHandler {
  private static final Logger log = LoggerFactory.getLogger(ProfilingRemoteConfigHandler.class);

  /**
   * While a config with this id is active, its configuration is applied but the profiler is kept
   * stopped.
   */
  static final String DISABLE_CONFIG_ID = "preset-disabled";

  private volatile ProfilingSystem profilingSystem;
  private final ConfigProvider localConfig;
  private final boolean runWithLocalConfig;
  private final Function<Map<String, String>, ConfigProvider> remoteConfigFactory;
  private final Predicate<ConfigProvider> anyProfilingEnabled;

  // the remote values currently applied, null when the local configuration applies
  private Map<String, String> appliedValues;
  // whether the currently applied configs include the disable config
  private boolean appliedDisable;
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

  /** Sets the profiling system; only configs received from now on are applied to it. */
  synchronized void setProfilingSystem(ProfilingSystem profilingSystem) {
    this.profilingSystem = profilingSystem;
  }

  @Override
  public synchronized void onConfigsChanged(String configId, Map<String, String> configsById) {
    if (closed) {
      log.debug("Profiling is shut down, ignoring DEBUG remote config");
      return;
    }
    if (profilingSystem == null) {
      log.debug("Profiler not yet initialized, dropping DEBUG remote config {}", configId);
      return;
    }
    applyConfigs(configId, configsById);
  }

  /** Applies the given configs to the profiling system. Must be called with the lock held. */
  private void applyConfigs(String configId, Map<String, String> configsById) {
    log.debug("DEBUG remote config received: " + configId);
    final Map<String, String> values =
        configsById.isEmpty() ? null : ProfilingConfigMerger.merge(configsById);
    final boolean disable = configsById.containsKey(DISABLE_CONFIG_ID);
    if (Objects.equals(values, appliedValues) && disable == appliedDisable) {
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
      if (disable) {
        // the configuration can only be applied while stopped, and the profiler stays stopped
        if (running) {
          log.debug("Profiling lifecycle: running -> stopped ({})", DISABLE_CONFIG_ID);
          profilingSystem.stop(false);
        }
        profilingSystem.reconfigure(config);
        log.debug("Applied {} configuration, profiling stays stopped", DISABLE_CONFIG_ID);
      } else if (!enabled) {
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
      appliedDisable = disable;
    } catch (UnsupportedEnvironmentException | RuntimeException e) {
      log.warn(SEND_TELEMETRY, "Failed to apply DEBUG remote config", e);
    }
  }

  /** Stops reacting to remote config, e.g. once profiling is shut down. */
  synchronized void close() {
    closed = true;
  }
}
