package com.datadog.profiling.agent;

import static com.datadog.profiling.controller.ProfilingSupport.isAllocationSamplerSafe;
import static com.datadog.profiling.controller.ProfilingSupport.isLiveHeapProfilingSafe;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isWallClockProfilerEnabled;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_ENABLED;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.ddprof.DatadogProfiler;
import com.datadog.profiling.remoteconfig.ProfilingConfigHandler;
import com.datadog.profiling.remoteconfig.RemoteConfiguration;
import com.datadog.profiling.remoteconfig.RemoteConfigurationStack;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts, restarts or stops the profiler according to the {@link RemoteConfiguration}s of the
 * priority stack, merged into one with the value of each feature coming from the highest priority
 * configuration that sets it. The merged configuration takes precedence over the local one for the
 * features it sets; the others keep their local value. When it sets no feature, the profiler is
 * stopped, as it only runs when remote config asks for it. The profiler is also stopped when the
 * resulting configuration has all profiling features disabled.
 *
 * <p>Whether something is enabled is judged from the modes of the Datadog profiler that the
 * resulting configuration enables, since that is what the remote features map to. A JFR-only setup
 * is not told apart: it follows the same modes. The modes that remote config doesn't set, like
 * native memory, keep their local value and keep the profiler running if they are enabled.
 *
 * <p>Known limitations:
 *
 * <ul>
 *   <li>Only the Datadog profiler is reconfigured. The JFR recording keeps its startup settings, so
 *       a feature that remote config disables can still be recorded by its JFR events, e.g. {@code
 *       jdk.ExecutionSample} for {@code cpuTime}.
 *   <li>On a JVM where the Datadog profiler can't do allocation or live heap profiling, e.g. Java
 *       8, a configuration that only enables {@code allocations} and/or {@code heapObjects} enables
 *       no Datadog profiler mode, so it doesn't start the profiler.
 *   <li>Remote config doesn't enable allocation or live heap profiling where they are not
 *       considered safe, see {@link #dropUnsafeEnablement}: a remote {@code true} is ignored there.
 *   <li>Wall clock profiling only samples the threads registered while tracing, which only happens
 *       when it was enabled locally at startup: enabling it remotely otherwise gives an empty wall
 *       clock profile, which is logged.
 * </ul>
 *
 * <p>Stopping a running profiler uploads what it recorded since its last snapshot first. That
 * upload is synchronous, so it holds up the remote config thread for at most the upload timeout.
 *
 * <p>When applying a configuration fails, or the profiler doesn't start, even later after the
 * startup delay, the next remote config change applies it again, even if it is the one that was
 * applied before.
 *
 * <p>A start honors the configured startup delay, with its randomization, counted from {@code
 * premain}: before it is over, the profiler starts when it is, however many times the configuration
 * changes in the meantime, and after it, right away where that is safe. Once a recording has run,
 * the JVM is past {@code premain}, and the profiler restarts right away.
 *
 * <p>This handler can be created before the profiler is initialized. Remote config is not delivered
 * again once it was, so the configs received before the profiler is available via {@link
 * #setProfilingSystem} are kept, only the latest ones, and applied when it is.
 */
final class ProfilingRemoteConfigHandler implements ProfilingConfigHandler {
  private static final Logger log = LoggerFactory.getLogger(ProfilingRemoteConfigHandler.class);

  // set once, never reset to null
  private volatile ProfilingSystem profilingSystem;
  private final ConfigProvider localConfig;
  private final Function<Map<String, String>, ConfigProvider> remoteConfigFactory;
  private final Predicate<ConfigProvider> anyProfilingEnabled;
  private final boolean startImmediately;

  // the latest configs not applied yet, e.g. received before the profiler was available
  private final AtomicReference<RemoteConfigurationStack> latestStack = new AtomicReference<>();
  // held by the thread applying configs, so that they are applied one at a time without holding a
  // lock across the profiler's start and stop
  private final AtomicBoolean applying = new AtomicBoolean();

  // only used by the thread holding the applying flag:
  // the merged configuration currently applied, null when none sets a feature
  private RemoteConfiguration appliedConfiguration;
  // whether the profiler is in the state that appliedConfiguration says; false after a failure
  private boolean stateKnown = true;
  // whether a recording was seen running, which means that the JVM is past premain
  private boolean recordingSeen;

  // set from any thread, so that closing doesn't wait for a configuration being applied
  private volatile boolean closed;

  /**
   * Creates a handler without a profiling system. The profiler must be set later via {@link
   * #setProfilingSystem}.
   *
   * @param startImmediately whether the profiler can start right away once the startup delay is
   *     over, rather than from the scheduler thread; not on a JVM where starting before {@code
   *     main} has started can crash it
   */
  ProfilingRemoteConfigHandler(ConfigProvider localConfig, boolean startImmediately) {
    this(
        null,
        localConfig,
        ConfigProvider::withRemoteOverride,
        DatadogProfiler::isAnyProfilingModeEnabled,
        startImmediately);
  }

  ProfilingRemoteConfigHandler(
      ProfilingSystem profilingSystem,
      ConfigProvider localConfig,
      Function<Map<String, String>, ConfigProvider> remoteConfigFactory,
      Predicate<ConfigProvider> anyProfilingEnabled,
      boolean startImmediately) {
    this.profilingSystem = profilingSystem;
    this.localConfig = localConfig;
    this.remoteConfigFactory = remoteConfigFactory;
    this.anyProfilingEnabled = anyProfilingEnabled;
    this.startImmediately = startImmediately;
  }

  /**
   * Sets the profiling system, and applies the configs that were received before, if any.
   *
   * <p>It can be called from {@code premain}, where the profiler only starts right away if that is
   * safe, see the constructor.
   */
  void setProfilingSystem(ProfilingSystem profilingSystem) {
    this.profilingSystem = profilingSystem;
    applyLatestConfigs();
  }

  @Override
  public void onConfigsChanged(RemoteConfigurationStack stack) {
    if (closed) {
      log.debug("Profiling is shut down, ignoring APM_PROFILING remote config");
      return;
    }
    latestStack.set(stack);
    if (profilingSystem == null) {
      log.debug(
          "Profiler not yet initialized, keeping APM_PROFILING remote config {} until it is",
          stack.configIds());
      return;
    }
    applyLatestConfigs();
  }

  /**
   * Applies the latest configs, unless another thread is applying configs, in which case that
   * thread applies them once it is done. Both the configs and the profiling system are published
   * before calling this, and checked again after releasing the flag, so that whichever thread comes
   * last sees both and applies the configs.
   */
  private void applyLatestConfigs() {
    while (!closed
        && profilingSystem != null
        && latestStack.get() != null
        && applying.compareAndSet(false, true)) {
      try {
        final RemoteConfigurationStack stack = latestStack.getAndSet(null);
        if (stack != null) {
          applyConfigs(stack);
        }
      } finally {
        applying.set(false);
      }
    }
  }

  /** Applies the given configs to the profiling system. Must be called with the flag held. */
  private void applyConfigs(RemoteConfigurationStack stack) {
    // the value of each feature comes from the highest priority configuration that sets it
    final RemoteConfiguration merged = stack.merged();
    final RemoteConfiguration remote = merged.isEmpty() ? null : merged;
    if (profilingSystem.isStarted()) {
      recordingSeen = true;
    }
    // a start that was delayed can fail later, on a thread that this one doesn't see
    if (stateKnown
        && !profilingSystem.isStartFailed()
        && Objects.equals(remote, appliedConfiguration)) {
      log.debug("APM_PROFILING remote config unchanged, nothing to do");
      return;
    }
    final ConfigProvider config;
    final boolean enabled;
    if (remote == null) {
      log.debug("No APM_PROFILING remote config sets a feature");
      config = localConfig;
      enabled = false;
    } else {
      log.debug("Applying APM_PROFILING remote config: {}", remote);
      final Map<String, String> values = remote.toConfigValues();
      dropUnsafeEnablement(values);
      warnIfWallClockCantSample(remote);
      config = remoteConfigFactory.apply(values);
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
        final boolean immediately =
            recordingSeen || (startImmediately && profilingSystem.isPastStartupDelay());
        profilingSystem.start(immediately);
        if (closed) {
          // shut down in the meantime, which is why the profiler didn't start
          return;
        }
        if (immediately && !profilingSystem.isStarted()) {
          // the start failed without throwing, e.g. the profiler isn't supported here: don't
          // leave it half started, and don't take the configuration for applied
          profilingSystem.stop(false);
          throw new IllegalStateException("The profiler did not start");
        }
        log.debug(
            "Profiling lifecycle: {} -> {} (remote configuration)",
            running ? "stopped" : "idle",
            immediately ? "running" : "starting after the startup delay");
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
   * Removes the remote enablement of allocation and live heap profiling on a JVM where they are not
   * considered safe, e.g. because of jmethodID crash bugs, so that the local default applies there.
   * An explicit local opt-in is still honored, as the local configuration decides. Disabling them
   * is always allowed.
   */
  static void dropUnsafeEnablement(Map<String, String> values) {
    if (!isAllocationSamplerSafe()) {
      dropEnablement(values, PROFILING_ALLOCATION_ENABLED);
      dropEnablement(values, PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED);
    }
    if (!isLiveHeapProfilingSafe()) {
      dropEnablement(values, PROFILING_HEAP_ENABLED);
    }
  }

  private static void dropEnablement(Map<String, String> values, String key) {
    if ("true".equals(values.get(key))) {
      values.remove(key);
      log.debug("Ignoring the remote enablement of {}, which is not safe on this JVM", key);
    }
  }

  // the threads are only registered for wall clock profiling when it is enabled at startup
  private void warnIfWallClockCantSample(RemoteConfiguration remote) {
    if (Boolean.TRUE.equals(remote.get(RemoteConfiguration.Feature.WALL_TIME))
        && !isWallClockProfilerEnabled(localConfig)) {
      log.warn(
          "Remote config enables wall clock profiling, which was disabled at startup: no thread is"
              + " registered for it, so the wall clock profile will be empty until the application"
              + " is restarted with it enabled");
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
