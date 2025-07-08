package com.datadog.profiling.controller.ddprof;

import com.datadog.profiling.controller.ProfilerSettingsSupport;
import com.datadog.profiling.ddprof.DatadogProfiler;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

public class DatadogProfilerSettings extends ProfilerSettingsSupport {

  private final DatadogProfiler datadogProfiler;

  public DatadogProfilerSettings(DatadogProfiler datadogProfiler) {
    super(ConfigProvider.getInstance(), null, false);
    this.datadogProfiler = datadogProfiler;
  }

  public void publish() {
    datadogProfiler.recordSetting(UPLOAD_PERIOD_KEY, String.valueOf(uploadPeriod), "seconds");
    datadogProfiler.recordSetting(UPLOAD_TIMEOUT_KEY, String.valueOf(uploadTimeout), "seconds");
    datadogProfiler.recordSetting(UPLOAD_COMPRESSION_KEY, uploadCompression);
    datadogProfiler.recordSetting(
        ALLOCATION_PROFILING_KEY, String.valueOf(allocationProfilingEnabled));
    datadogProfiler.recordSetting(HEAP_PROFILING_KEY, String.valueOf(heapProfilingEnabled));
    datadogProfiler.recordSetting(FORCE_START_FIRST_KEY, String.valueOf(startForceFirst));
    datadogProfiler.recordSetting(HOTSPOTS_KEY, String.valueOf(hotspotsEnabled));
    datadogProfiler.recordSetting(ENDPOINTS_KEY, String.valueOf(endpointsEnabled));
    datadogProfiler.recordSetting(AUXILIARY_PROFILER_KEY, auxiliaryProfiler);
    datadogProfiler.recordSetting(PERF_EVENTS_PARANOID_KEY, perfEventsParanoid);
    datadogProfiler.recordSetting(NATIVE_STACKS_KEY, String.valueOf(hasNativeStacks));
    datadogProfiler.recordSetting(JFR_IMPLEMENTATION_KEY, "ddprof");
    datadogProfiler.recordSetting(
        "ddprof " + STACK_DEPTH_KEY,
        String.valueOf(requestedStackDepth)); // ddprof-java will accept the requested stack depth
    datadogProfiler.recordSetting(SELINUX_STATUS_KEY, seLinuxStatus);
    if (serviceInstrumentationType != null) {
      datadogProfiler.recordSetting(SERVICE_INSTRUMENTATION_TYPE, serviceInstrumentationType);
    }
    if (serviceInjection != null) {
      datadogProfiler.recordSetting(SERVICE_INJECTION, serviceInjection);
    }
    datadogProfiler.recordSetting(
        PROFILER_ACTIVATION, profilerActivationSetting.enablement.getAlias());
    datadogProfiler.recordSetting(
        SSI_MECHANISM, profilerActivationSetting.ssiMechanism.name().toLowerCase());
  }

  @Override
  protected String profilerKind() {
    return "datadog";
  }
}
