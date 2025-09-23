package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.ProfilerSettingsSupport;
import com.datadog.profiling.controller.openjdk.events.ProfilerSettingEvent;
import datadog.common.version.VersionInfo;
import datadog.environment.JavaVirtualMachine;
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
  private final boolean isDdprofActive;

  public JfrProfilerSettings(
      ConfigProvider configProvider,
      ControllerContext.Snapshot context,
      boolean hasJfrStackDepthApplied) {
    super(configProvider, context.getDatadogProfilerUnavailableReason(), hasJfrStackDepthApplied);
    this.jfrImplementation =
        Platform.isNativeImage()
            ? "native-image"
            : (JavaVirtualMachine.isOracleJDK8() ? "oracle" : "openjdk");
    this.isDdprofActive = context.isDatadogProfilerEnabled();
  }

  public void publish() {
    if (new ProfilerSettingEvent(null, null, null).isEnabled()) {
      new ProfilerSettingEvent(VERSION_KEY, VersionInfo.VERSION).commit();
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
      new ProfilerSettingEvent(
              "JFR " + STACK_DEPTH_KEY,
              String.valueOf(hasJfrStackDepthApplied ? requestedStackDepth : jfrStackDepth))
          .commit();
      if (isDdprofActive) {
        // emit this setting only if datadog profiler is also active
        new ProfilerSettingEvent(
                "ddprof " + STACK_DEPTH_KEY,
                String.valueOf(hasJfrStackDepthApplied ? requestedStackDepth : jfrStackDepth))
            .commit();
      }
      new ProfilerSettingEvent(SELINUX_STATUS_KEY, seLinuxStatus).commit();
      if (ddprofUnavailableReason != null) {
        new ProfilerSettingEvent(DDPROF_UNAVAILABLE_REASON_KEY, ddprofUnavailableReason).commit();
      }
      if (serviceInstrumentationType != null) {
        new ProfilerSettingEvent(SERVICE_INSTRUMENTATION_TYPE, serviceInstrumentationType).commit();
      }
      if (serviceInjection != null) {
        new ProfilerSettingEvent(SERVICE_INJECTION, serviceInjection).commit();
      }
      new ProfilerSettingEvent(PROFILER_ACTIVATION, profilerActivationSetting.enablement.getAlias())
          .commit();
      new ProfilerSettingEvent(
              SSI_MECHANISM, profilerActivationSetting.ssiMechanism.name().toLowerCase())
          .commit();
    }
  }

  @Override
  protected String profilerKind() {
    return "jfr";
  }
}
