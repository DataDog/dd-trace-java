package com.datadog.profiling.controller.ddprof;

import com.datadog.profiling.controller.ProfilerSettingsSupport;
import com.datadog.profiling.ddprof.DatadogProfiler;

public class DatadogProfilerSettings extends ProfilerSettingsSupport {

  private final DatadogProfiler datadogProfiler;

  public DatadogProfilerSettings(DatadogProfiler datadogProfiler) {
    this.datadogProfiler = datadogProfiler;
  }

  public void publish() {
    datadogProfiler.recordSetting("Upload Period", String.valueOf(uploadPeriod), "seconds");
    datadogProfiler.recordSetting("Upload Timeout", String.valueOf(uploadTimeout), "seconds");
    datadogProfiler.recordSetting("Upload Compression", uploadCompression);
    datadogProfiler.recordSetting(
        "Allocation Profiling", String.valueOf(allocationProfilingEnabled));
    datadogProfiler.recordSetting("Heap Profiling", String.valueOf(heapProfilingEnabled));
    datadogProfiler.recordSetting("Force Start-First", String.valueOf(startForceFirst));
    datadogProfiler.recordSetting("Hotspots", String.valueOf(hotspotsEnabled));
    datadogProfiler.recordSetting("Endpoints", String.valueOf(endpointsEnabled));
    datadogProfiler.recordSetting("Auxiliary Profiler", auxiliaryProfiler);
    datadogProfiler.recordSetting("perf_events_paranoid", perfEventsParanoid);
    datadogProfiler.recordSetting("Native Stacks", String.valueOf(hasNativeStacks));
  }
}
