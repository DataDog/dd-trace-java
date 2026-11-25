package com.datadog.profiling.agent;

import static com.datadog.profiling.controller.ProfilingSupport.isAllocationSamplerSafe;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getWallContextFilter;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isAllocationProfilingEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isCpuProfilerEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isLiveHeapSizeTrackingEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isMemoryLeakProfilingEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isWallClockProfilerEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isWallClockProfilerEnabledByDefault;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.ALLOCATIONS;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.CPU_TIME;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.HEAP_OBJECTS;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.HEAP_SIZE;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.WALL_TIME;
import static com.datadog.profiling.utils.ProfilingMode.ALLOCATION;
import static com.datadog.profiling.utils.ProfilingMode.CPU;
import static com.datadog.profiling.utils.ProfilingMode.LIVEHEAP_SIZE;
import static com.datadog.profiling.utils.ProfilingMode.MEMLEAK;
import static com.datadog.profiling.utils.ProfilingMode.WALL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_ENABLED;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.ddprof.DatadogProfiler;
import com.datadog.profiling.remoteconfig.ProfilingConfigHandler;
import com.datadog.profiling.remoteconfig.RemoteConfiguration;
import com.datadog.profiling.remoteconfig.RemoteConfigurationStack;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.api.ConfigCollector;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts, restarts or stops the profiler according to the {@link RemoteConfiguration}s of the
 * priority stack, merged into one with the value of each feature coming from the highest priority
 * configuration that sets it. The merged configuration takes precedence over the local one for the
 * features it sets; the others keep their local value.
 *
 * <ul>
 *   <li>When there is no profiling config, e.g. none was received yet, they were all removed, or
 *       the payloads received are not profiling configs, the profiler is idle: it only runs when
 *       remote config asks for it.
 *   <li>When there are profiling configs, but they set no feature, the local configuration applies
 *       as is: the profiler runs if it enables something, even if it was idle.
 *   <li>When the merged configuration disables all the remote configurable features, the profiler
 *       is stopped, without merging the local configuration.
 *   <li>Otherwise, the profiler is stopped when the resulting configuration has all profiling
 *       features disabled.
 * </ul>
 *
 * <p>Whether something is enabled is judged from the modes of the Datadog profiler that the
 * resulting configuration enables, since that is what the remote features map to. A JFR-only setup
 * is not told apart: it follows the same modes. Unless remote config disables all the remote
 * configurable features, the modes that it doesn't set, like native memory, keep their local value
 * and keep the profiler running if they are enabled.
 *
 * <p>The remote features switch Datadog profiler modes on or off over the local configuration,
 * which still gives every other setting, e.g. the sampling intervals: {@code cpuTime}, {@code
 * wallTime}, {@code allocations}, {@code heapObjects} and {@code heapSize} switch {@link
 * ProfilingMode#CPU}, {@link ProfilingMode#WALL}, {@link ProfilingMode#ALLOCATION}, {@link
 * ProfilingMode#MEMLEAK} and {@link ProfilingMode#LIVEHEAP_SIZE}. The values applied are reported
 * to config telemetry with the remote config origin, under the names of the local settings they
 * override.
 *
 * <p>Known limitations:
 *
 * <ul>
 *   <li>Only the Datadog profiler is reconfigured. The JFR recording keeps its startup settings, so
 *       a feature that remote config disables can still be recorded by its JFR events, e.g. {@code
 *       jdk.ExecutionSample} for {@code cpuTime}.
 *   <li>On a JVM where the Datadog profiler can't do allocation or live heap profiling, e.g. Java
 *       8, a configuration that only enables {@code allocations}, {@code heapObjects} and/or {@code
 *       heapSize} enables no Datadog profiler mode, so it doesn't start the profiler.
 *   <li>Remote config doesn't enable allocation or live heap profiling where they are not
 *       considered safe, nor wall clock profiling where it is disabled unless opted in, e.g. on J9,
 *       see {@link #modeOverrides}: a remote {@code true} is ignored there, and the local
 *       configuration decides.
 *   <li>The Datadog profiler only tracks the size of the live heap as part of live heap profiling.
 *       So {@code heapSize} enabled with {@code heapObjects} disabled still samples the live heap
 *       objects.
 *   <li>With wall context filtering, the default when tracing is enabled, wall clock profiling only
 *       samples the threads registered while tracing, which only happens when it was enabled
 *       locally at startup: enabling it remotely otherwise gives an empty wall clock profile, which
 *       is logged. Without the filter, e.g. when tracing is disabled, it samples every thread.
 * </ul>
 *
 * <p>Stopping a running profiler uploads what it recorded since its last snapshot first. That
 * upload is synchronous, so it holds up the remote config thread, which all the remote config
 * products share. Before it, the stop waits for the profiler's scheduler thread to end, which is
 * right away unless that thread is taking a periodic snapshot, and at most 10 seconds. So a stop
 * holds up the remote config thread for at most 10 seconds plus the upload timeout, and the final
 * snapshot is skipped if that thread didn't end in time, as it is uploading already.
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

  /** Set once, never reset to null. */
  private volatile ProfilingSystem profilingSystem;

  private final ConfigProvider localConfig;
  private final BiPredicate<ConfigProvider, Map<ProfilingMode, Boolean>> anyProfilingEnabled;
  private final boolean startImmediately;

  /** The latest configs not applied yet, e.g. received before the profiler was available. */
  private final AtomicReference<RemoteConfigurationStack> latestStack = new AtomicReference<>();

  /**
   * Held by the thread applying configs, so that they are applied one at a time without holding a
   * lock across the profiler's start and stop.
   */
  private final AtomicBoolean applying = new AtomicBoolean();

  /**
   * Whether remote config overrides were reported to telemetry, so that it is told the local values
   * back. Only accessed while applying, by one thread at a time.
   */
  private boolean reportedToTelemetry;

  // only used by the thread holding the applying flag

  /**
   * The merged configuration currently applied, null when there is no profiling config, and {@link
   * RemoteConfiguration#EMPTY} when there are some, but none sets a feature.
   */
  private RemoteConfiguration appliedConfiguration;

  /**
   * Whether the profiler is in the state that {@link #appliedConfiguration} says; false after a
   * failure. Initially idle, as without profiling config.
   */
  private boolean stateKnown = true;

  /** Whether a recording was seen running, which means that the JVM is past premain. */
  private boolean recordingSeen;

  /** Set from any thread, so that closing doesn't wait for a configuration being applied. */
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
    this(null, localConfig, DatadogProfiler::isAnyProfilingModeEnabled, startImmediately);
  }

  /**
   * @param anyProfilingEnabled whether the local configuration, with the given mode overrides,
   *     enables any profiling
   */
  ProfilingRemoteConfigHandler(
      ProfilingSystem profilingSystem,
      ConfigProvider localConfig,
      BiPredicate<ConfigProvider, Map<ProfilingMode, Boolean>> anyProfilingEnabled,
      boolean startImmediately) {
    this.profilingSystem = profilingSystem;
    this.localConfig = localConfig;
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
    // the value of each feature comes from the highest priority configuration that sets it. The
    // stack only holds profiling configs: the payloads that are not, or are dropped, aren't in it
    final RemoteConfiguration remote = stack.isEmpty() ? null : stack.merged();
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
    final Map<ProfilingMode, Boolean> overrides =
        remote == null ? emptyMap() : modeOverrides(remote, localConfig);
    final boolean enabled;
    if (remote == null) {
      log.debug("No APM_PROFILING remote config, the profiler is idle");
      enabled = false;
    } else if (remote.isEmpty()) {
      log.debug("No APM_PROFILING remote config sets a feature, applying the local configuration");
      enabled = anyProfilingEnabled.test(localConfig, overrides);
    } else if (remote.disablesEverything()) {
      // not merged with the local configuration: what it enables doesn't keep the profiler running
      log.debug(
          "APM_PROFILING remote config disables all the remote configurable features: {}", remote);
      enabled = false;
    } else {
      log.debug(
          "Applying APM_PROFILING remote config: {}, as mode overrides {}", remote, overrides);
      warnIfWallClockCantSample(remote);
      // the modes that the remote config doesn't set keep their local value
      enabled = anyProfilingEnabled.test(localConfig, overrides);
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
        profilingSystem.reconfigure(localConfig, overrides);
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
      reportToTelemetry(overrides);
    } catch (UnsupportedEnvironmentException | RuntimeException e) {
      // the profiler may have been stopped before the failure, so its state isn't the one of the
      // last applied configuration anymore: apply the next one even if it is the same
      stateKnown = false;
      log.warn(SEND_TELEMETRY, "Failed to apply APM_PROFILING remote config", e);
    }
  }

  /** The local settings that each mode overrides, as reported to config telemetry. */
  private static final Map<ProfilingMode, String> OVERRIDDEN_SETTINGS = overriddenSettings();

  private static Map<ProfilingMode, String> overriddenSettings() {
    Map<ProfilingMode, String> settings = new EnumMap<>(ProfilingMode.class);
    settings.put(CPU, PROFILING_DATADOG_PROFILER_CPU_ENABLED);
    settings.put(WALL, PROFILING_DATADOG_PROFILER_WALL_ENABLED);
    settings.put(ALLOCATION, PROFILING_ALLOCATION_ENABLED);
    settings.put(MEMLEAK, PROFILING_HEAP_ENABLED);
    settings.put(LIVEHEAP_SIZE, PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE);
    return unmodifiableMap(settings);
  }

  /**
   * The Datadog profiler modes that a remote configuration switches on or off, one per feature it
   * sets. A remote {@code true} is ignored where the mode is not enabled without an explicit local
   * opt-in, so that the local configuration decides there, and an opt-in is still honored:
   *
   * <ul>
   *   <li>allocation and live heap profiling on a JVM where the Datadog profiler's allocation
   *       sampler is not considered safe, e.g. because of jmethodID crash bugs;
   *   <li>wall clock profiling where it is disabled unless opted in, i.e. on J9, in ultra minimal
   *       mode, and when tracing is disabled.
   * </ul>
   *
   * Disabling them is always allowed.
   *
   * @param localConfig the local configuration, which tells where wall clock profiling is disabled
   *     unless opted in
   */
  static Map<ProfilingMode, Boolean> modeOverrides(
      RemoteConfiguration remote, ConfigProvider localConfig) {
    Map<ProfilingMode, Boolean> overrides = new EnumMap<>(ProfilingMode.class);
    boolean samplerSafe = isAllocationSamplerSafe();
    override(overrides, CPU, remote.get(CPU_TIME), true);
    override(
        overrides, WALL, remote.get(WALL_TIME), isWallClockProfilerEnabledByDefault(localConfig));
    override(overrides, ALLOCATION, remote.get(ALLOCATIONS), samplerSafe);
    override(overrides, MEMLEAK, remote.get(HEAP_OBJECTS), samplerSafe);
    override(overrides, LIVEHEAP_SIZE, remote.get(HEAP_SIZE), samplerSafe);
    return unmodifiableMap(overrides);
  }

  private static void override(
      Map<ProfilingMode, Boolean> overrides,
      ProfilingMode mode,
      Boolean value,
      boolean enablementSafe) {
    if (value == null) {
      return;
    }
    if (value && !enablementSafe) {
      log.debug(
          "Ignoring the remote enablement of {}, which is only enabled here by a local opt-in",
          mode);
      return;
    }
    overrides.put(mode, value);
  }

  /**
   * Reports the values of the overridden settings to config telemetry, with the remote config
   * origin, under the names of the local settings, like the other remote configurable settings:
   * once remote config has overridden one, each applied configuration reports them all, with the
   * local value of those it doesn't override, e.g. after an override is removed, which would
   * otherwise keep its remote value in telemetry. Nothing is reported while remote config has never
   * overridden any.
   */
  private void reportToTelemetry(Map<ProfilingMode, Boolean> overrides) {
    if (overrides.isEmpty() && !reportedToTelemetry) {
      return;
    }
    ConfigCollector.get().putRemote(telemetryValues(overrides, localConfig));
    reportedToTelemetry = true;
  }

  /**
   * @return the value of each overridden setting: the override if there is one, the local value
   *     otherwise, by the name of the setting
   */
  static Map<String, Object> telemetryValues(
      Map<ProfilingMode, Boolean> overrides, ConfigProvider localConfig) {
    Map<String, Object> values = new HashMap<>();
    for (Map.Entry<ProfilingMode, String> setting : OVERRIDDEN_SETTINGS.entrySet()) {
      Boolean override = overrides.get(setting.getKey());
      values.put(
          setting.getValue(),
          override != null ? override : localValue(setting.getKey(), localConfig));
    }
    return values;
  }

  /** The value of the local setting that the mode overrides. */
  private static boolean localValue(ProfilingMode mode, ConfigProvider localConfig) {
    switch (mode) {
      case CPU:
        return isCpuProfilerEnabled(localConfig);
      case WALL:
        return isWallClockProfilerEnabled(localConfig);
      case ALLOCATION:
        return isAllocationProfilingEnabled(localConfig);
      case MEMLEAK:
        return isMemoryLeakProfilingEnabled(localConfig);
      case LIVEHEAP_SIZE:
        return isLiveHeapSizeTrackingEnabled(localConfig);
      default:
        throw new IllegalArgumentException("Not an overridden mode: " + mode);
    }
  }

  /**
   * Warns that the threads are only registered for wall clock profiling when it is enabled at
   * startup, see {@link #wallClockCantSample}.
   */
  private void warnIfWallClockCantSample(RemoteConfiguration remote) {
    if (wallClockCantSample(remote, localConfig)) {
      log.warn(
          "Remote config enables wall clock profiling, which was disabled at startup: no thread is"
              + " registered for it, so the wall clock profile will be empty until the application"
              + " is restarted with it enabled");
    }
  }

  /**
   * Whether remote config enables wall clock profiling where it can't sample anything: with the
   * wall context filter, it only samples the threads registered while tracing, which are only
   * registered when it is enabled locally at startup. Without the filter, which the Datadog
   * profiler turns off when tracing is disabled, it samples every thread.
   */
  static boolean wallClockCantSample(RemoteConfiguration remote, ConfigProvider localConfig) {
    return Boolean.TRUE.equals(remote.get(WALL_TIME))
        // where it is disabled unless opted in, the remote enablement is ignored, see modeOverrides
        && isWallClockProfilerEnabledByDefault(localConfig)
        && !isWallClockProfilerEnabled(localConfig)
        && getWallContextFilter(localConfig);
  }

  /**
   * Stops reacting to remote config, e.g. once profiling is shut down. It doesn't wait for a
   * configuration that is being applied, which stops before starting the profiler if it sees this.
   */
  void close() {
    closed = true;
  }
}
