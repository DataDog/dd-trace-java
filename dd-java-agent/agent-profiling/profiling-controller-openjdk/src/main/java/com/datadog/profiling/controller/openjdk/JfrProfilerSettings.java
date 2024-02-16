package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.ProfilerSettingsSupport;
import com.datadog.profiling.controller.openjdk.events.ProfilerSettingEvent;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

/** Capture the profiler config first and allow emitting the setting events per each recording. */
final class JfrProfilerSettings extends ProfilerSettingsSupport {
  private static final String JFP_TEMPLATE_OVERRIDE_PROFILING_KEY =
      "JFP Template Override Profiling";
  private static final String EXCEPTION_SAMPLE_RATE_LIMIT_KEY = "Exception Sample Rate Limit";
  private static final String EXCEPTION_HISTO_REPORT_LIMIT_KEY = "Exception Histo Report Limit";
  private static final String EXCEPTION_HISTO_SIZE_LIMIT_KEY = "Exception Histo Size Limit";
  private final String jfrImplementation;

  private static final class SingletonHolder {
    private static final JfrProfilerSettings INSTANCE = new JfrProfilerSettings();
  }

  public JfrProfilerSettings() {
    super(ConfigProvider.getInstance());
    this.jfrImplementation =
        Platform.isNativeImage()
            ? "native-image"
            : (Platform.isOracleJDK8() ? "oracle" : "openjdk");
  }

  public static JfrProfilerSettings instance() {
    return SingletonHolder.INSTANCE;
  }

  public void publish() {
    if (new ProfilerSettingEvent(null, null, null).isEnabled()) {
      new ProfilerSettingEvent(UPLOAD_PERIOD_KEY, String.valueOf(uploadPeriod), "seconds").commit();
      new ProfilerSettingEvent(UPLOAD_TIMEOUT_KEY, String.valueOf(uploadTimeout), "seconds")
          .commit();
      new ProfilerSettingEvent(UPLOAD_COMPRESSION_KEY, uploadCompression).commit();
      new ProfilerSettingEvent(ALLOCATION_PROFILING_KEY, String.valueOf(allocationProfilingEnabled))
          .commit();
      new ProfilerSettingEvent(HEAP_PROFILING_KEY, String.valueOf(heapProfilingEnabled)).commit();
      new ProfilerSettingEvent(FORCE_START_FIRST_KEY, String.valueOf(startForceFirst)).commit();
      new ProfilerSettingEvent(
              JFP_TEMPLATE_OVERRIDE_PROFILING_KEY, String.valueOf(templateOverride))
          .commit();
      new ProfilerSettingEvent(
              EXCEPTION_SAMPLE_RATE_LIMIT_KEY,
              String.valueOf(exceptionSampleLimit),
              "exceptions/second")
          .commit();
      new ProfilerSettingEvent(
              EXCEPTION_HISTO_REPORT_LIMIT_KEY, String.valueOf(exceptionHistogramTopItems))
          .commit();
      new ProfilerSettingEvent(
              EXCEPTION_HISTO_SIZE_LIMIT_KEY, String.valueOf(exceptionHistogramMaxSize))
          .commit();
      new ProfilerSettingEvent(HOTSPOTS_KEY, String.valueOf(hotspotsEnabled)).commit();
      new ProfilerSettingEvent(ENDPOINTS_KEY, String.valueOf(endpointsEnabled)).commit();
      new ProfilerSettingEvent(AUXILIARY_PROFILER_KEY, auxiliaryProfiler).commit();
      new ProfilerSettingEvent(PERF_EVENTS_PARANOID_KEY, perfEventsParanoid).commit();
      new ProfilerSettingEvent(NATIVE_STACKS_KEY, String.valueOf(hasNativeStacks)).commit();
      new ProfilerSettingEvent(JFR_IMPLEMENTATION_KEY, jfrImplementation).commit();
      if (hasJfrStackDepthApplied) {
        new ProfilerSettingEvent(STACK_DEPTH_KEY, String.valueOf(stackDepth)).commit();
      }
      new ProfilerSettingEvent(SELINUX_STATUS_KEY, seLinuxStatus).commit();
    }
  }
}
