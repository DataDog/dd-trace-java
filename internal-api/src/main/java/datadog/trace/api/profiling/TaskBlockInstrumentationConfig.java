package datadog.trace.api.profiling;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DELEGATE_MONITOR_EVENTS_TO_AGENT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DELEGATE_MONITOR_EVENTS_TO_AGENT_DEFAULT;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Collections;

/** Shared configuration gates for Java-level {@code datadog.TaskBlock} instrumentations. */
public final class TaskBlockInstrumentationConfig {
  private static final Iterable<String> OBJECT_WAIT_INTEGRATION =
      Collections.singletonList("object-wait");
  private static final Iterable<String> SYNCHRONIZED_CONTENTION_INTEGRATION =
      Collections.singletonList("synchronized-contention");

  private TaskBlockInstrumentationConfig() {}

  public static boolean isWallPrecheckEnabled(final ConfigProvider configProvider) {
    return configProvider.getBoolean(
        PROFILING_DATADOG_PROFILER_WALL_PRECHECK, PROFILING_DATADOG_PROFILER_WALL_PRECHECK_DEFAULT);
  }

  public static boolean isMonitorEventDelegationRequested(final ConfigProvider configProvider) {
    return configProvider.getBoolean(
        PROFILING_DELEGATE_MONITOR_EVENTS_TO_AGENT,
        PROFILING_DELEGATE_MONITOR_EVENTS_TO_AGENT_DEFAULT);
  }

  /**
   * Configuration-only predicate for Java ownership of the JDK 21+ monitor TaskBlock populations.
   *
   * <p>Native JVMTI exposes Object.wait and synchronized contention behind one monitor-events
   * capability, so Java ownership is all-or-native: both Java modules must be enabled together.
   * Callers still apply runtime gates such as JDK version, profiler enablement, and ultra-minimal
   * mode where appropriate.
   */
  public static boolean shouldUseJavaMonitorTaskBlockInstrumentation(
      final ConfigProvider configProvider, final InstrumenterConfig instrumenterConfig) {
    return isMonitorEventDelegationRequested(configProvider)
        && isWallPrecheckEnabled(configProvider)
        && instrumenterConfig.isIntegrationsEnabled()
        && instrumenterConfig.isIntegrationEnabled(OBJECT_WAIT_INTEGRATION, true)
        && instrumenterConfig.isIntegrationEnabled(SYNCHRONIZED_CONTENTION_INTEGRATION, true);
  }
}
