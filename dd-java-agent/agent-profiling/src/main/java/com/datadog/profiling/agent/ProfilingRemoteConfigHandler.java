package com.datadog.profiling.agent;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.ddprof.DatadogProfiler;
import com.datadog.profiling.ddprof.DatadogProfilerConfig;
import com.datadog.profiling.remoteconfig.ProfilingConfigHandler;
import com.datadog.profiling.remoteconfig.ProfilingConfigMerger;
import com.datadog.profiling.remoteconfig.RemoteConfiguration;
import com.datadog.profiling.remoteconfig.RemoteConfigurationStack;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts, restarts or stops the profiler according to the {@link RemoteConfiguration}s of the
 * priority stack, merged into one with the value of each feature coming from the highest priority
 * configuration that sets it. The merged configuration takes precedence over the local one for the
 * features it sets; the others keep their local value. When it sets no feature, the local
 * configuration applies. The profiler is stopped when the resulting configuration has all profiling
 * features disabled.
 *
 * <p>Whether something is enabled is judged from the modes of the Datadog profiler that the
 * resulting configuration enables, since that is what the remote features map to. A JFR-only setup
 * is not told apart: it follows the same modes. The modes that remote config doesn't set, like
 * native memory, keep their local value and keep the profiler running if they are enabled.
 *
 * <p>Stopping a running profiler uploads what it recorded since its last snapshot first. That
 * upload is synchronous, so it holds up the remote config thread for at most the upload timeout.
 *
 * <p>When applying a configuration fails, or the profiler doesn't start, the next remote config
 * change applies it again, even if it is the one that was applied before.
 *
 * <p>This handler can be created before the profiler is initialized. Remote config is not delivered
 * again once it was, so the configs received before the profiler is available via {@link
 * #setProfilingSystem} are kept, only the latest ones, and applied when it is.
 */
final class ProfilingRemoteConfigHandler implements ProfilingConfigHandler {
  private static final Logger log = LoggerFactory.getLogger(ProfilingRemoteConfigHandler.class);

  private volatile ProfilingSystem profilingSystem;
  private final ConfigProvider localConfig;
  private final boolean runWithLocalConfig;
  private final Function<Map<String, String>, ConfigProvider> remoteConfigFactory;
  private final Predicate<ConfigProvider> anyProfilingEnabled;
  private final Predicate<ConfigProvider> liveHeapEnabled;
  private final boolean startImmediately;

  // the latest configs received before the profiler was available, if any
  private RemoteConfigurationStack pendingStack;
  // the merged configuration currently applied, null when the local configuration applies
  private RemoteConfiguration appliedConfiguration;
  // whether the profiler is in the state that appliedConfiguration says; false after a failure
  private boolean stateKnown = true;
  // volatile rather than guarded, so that closing doesn't wait for a configuration being applied
  private volatile boolean closed;

  /**
   * Creates a handler without a profiling system. The profiler must be set later via {@link
   * #setProfilingSystem}.
   *
   * @param runWithLocalConfig whether the profiler should run when no remote config is active
   * @param startImmediately whether the profiler can start right away, instead of after the usual
   *     startup delay; not on a JVM where starting before {@code main} has started can crash it
   */
  ProfilingRemoteConfigHandler(
      ConfigProvider localConfig, boolean runWithLocalConfig, boolean startImmediately) {
    this(
        null,
        localConfig,
        runWithLocalConfig,
        ConfigProvider::withRemoteOverride,
        DatadogProfiler::isAnyProfilingModeEnabled,
        DatadogProfilerConfig::isMemoryLeakProfilingEnabled,
        startImmediately);
  }

  ProfilingRemoteConfigHandler(
      ProfilingSystem profilingSystem,
      ConfigProvider localConfig,
      boolean runWithLocalConfig,
      Function<Map<String, String>, ConfigProvider> remoteConfigFactory,
      Predicate<ConfigProvider> anyProfilingEnabled,
      Predicate<ConfigProvider> liveHeapEnabled,
      boolean startImmediately) {
    this.profilingSystem = profilingSystem;
    this.localConfig = localConfig;
    this.runWithLocalConfig = runWithLocalConfig;
    this.remoteConfigFactory = remoteConfigFactory;
    this.anyProfilingEnabled = anyProfilingEnabled;
    this.liveHeapEnabled = liveHeapEnabled;
    this.startImmediately = startImmediately;
  }

  /**
   * Sets the profiling system, and applies the configs that were received before, if any.
   *
   * <p>It can be called from {@code premain}, where the profiler only starts right away if that is
   * safe, see the constructor.
   */
  synchronized void setProfilingSystem(ProfilingSystem profilingSystem) {
    this.profilingSystem = profilingSystem;
    if (pendingStack != null) {
      RemoteConfigurationStack pending = pendingStack;
      pendingStack = null;
      if (closed) {
        log.debug(
            "Profiling is shut down, ignoring the APM_PROFILING remote config received early");
        return;
      }
      log.debug(
          "Applying the APM_PROFILING remote config received before the profiler was ready: {}",
          pending.configIds());
      applyConfigs(pending);
    }
  }

  @Override
  public synchronized void onConfigsChanged(RemoteConfigurationStack stack) {
    if (closed) {
      log.debug("Profiling is shut down, ignoring APM_PROFILING remote config");
      return;
    }
    if (profilingSystem == null) {
      log.debug(
          "Profiler not yet initialized, keeping APM_PROFILING remote config {} until it is",
          stack.configIds());
      pendingStack = stack;
      return;
    }
    applyConfigs(stack);
  }

  /** Applies the given configs to the profiling system. Must be called with the lock held. */
  private void applyConfigs(RemoteConfigurationStack stack) {
    // the value of each feature comes from the highest priority configuration that sets it
    final RemoteConfiguration merged = ProfilingConfigMerger.merge(stack);
    final RemoteConfiguration remote = merged.isEmpty() ? null : merged;
    if (stateKnown && Objects.equals(remote, appliedConfiguration)) {
      log.debug("APM_PROFILING remote config unchanged, nothing to do");
      return;
    }
    final ConfigProvider config;
    final boolean enabled;
    if (remote == null) {
      log.debug(
          "No APM_PROFILING remote config sets a feature, reverting to the local configuration");
      config = localConfig;
      enabled = runWithLocalConfig;
    } else {
      log.debug("Applying APM_PROFILING remote config: {}", remote);
      config = remoteConfigFactory.apply(remote.toConfigValues(liveHeapEnabled.test(localConfig)));
      // the features that the remote config doesn't set keep their local value
      enabled = anyProfilingEnabled.test(config);
    }

    final boolean running = profilingSystem.isRunning();
    try {
      if (!enabled) {
        // all the profiling features are disabled by the values, whatever the configs' ids
        if (running) {
          log.debug("Profiling lifecycle: running -> stopped (all profiling disabled)");
          profilingSystem.stop(true);
        } else {
          log.debug("Profiling lifecycle: idle -> idle (all profiling disabled)");
        }
      } else {
        if (running) {
          log.debug("Profiling lifecycle: running -> stopped (restarting with new configuration)");
          profilingSystem.stop(true);
        }
        if (closed) {
          log.debug("Profiling is shut down, not starting the profiler");
          return;
        }
        profilingSystem.reconfigure(config);
        profilingSystem.start(startImmediately);
        if (startImmediately && !profilingSystem.isStarted()) {
          // the start failed without throwing, e.g. the profiler isn't supported here: don't
          // leave it half started, and don't take the configuration for applied
          profilingSystem.stop(false);
          throw new IllegalStateException("The profiler did not start");
        }
        log.debug(
            "Profiling lifecycle: {} -> {} ({} configuration)",
            running ? "stopped" : "idle",
            startImmediately ? "running" : "starting after the startup delay",
            remote == null ? "local" : "remote");
      }
      appliedConfiguration = remote;
      stateKnown = true;
    } catch (UnsupportedEnvironmentException | RuntimeException e) {
      // the profiler may have been stopped before the failure, so its state isn't the one of the
      // last applied configuration anymore: apply the next one even if it is the same
      stateKnown = false;
      log.warn(SEND_TELEMETRY, "Failed to apply APM_PROFILING remote config", e);
    }
  }

  /**
   * Stops reacting to remote config, e.g. once profiling is shut down. It doesn't wait for a
   * configuration that is being applied, which stops before starting the profiler if it sees this.
   */
  void close() {
    closed = true;
  }
}
