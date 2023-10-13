package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.ProfilerSettingsSupport;
import com.datadog.profiling.controller.openjdk.events.ProfilerSettingEvent;

/** Capture the profiler config first and allow emitting the setting events per each recording. */
final class JfrProfilerSettings extends ProfilerSettingsSupport {
  public void publish() {
    if (new ProfilerSettingEvent(null, null, null).isEnabled()) {
      new ProfilerSettingEvent("Upload Period", String.valueOf(uploadPeriod), "seconds").commit();
      new ProfilerSettingEvent("Upload Timeout", String.valueOf(uploadTimeout), "seconds").commit();
      new ProfilerSettingEvent("Upload Compression", uploadCompression).commit();
      new ProfilerSettingEvent("Allocation Profiling", String.valueOf(allocationProfilingEnabled))
          .commit();
      new ProfilerSettingEvent("Heap Profiling", String.valueOf(heapProfilingEnabled)).commit();
      new ProfilerSettingEvent("Force Start-First", String.valueOf(startForceFirst)).commit();
      new ProfilerSettingEvent("JFP Template Override Profiling", String.valueOf(templateOverride))
          .commit();
      new ProfilerSettingEvent(
              "Exception Sample Rate Limit",
              String.valueOf(exceptionSampleLimit),
              "exceptions/second")
          .commit();
      new ProfilerSettingEvent(
              "Exception Histo Report Limit", String.valueOf(exceptionHistogramTopItems))
          .commit();
      new ProfilerSettingEvent(
              "Exception Histo Size Limit", String.valueOf(exceptionHistogramMaxSize))
          .commit();
      new ProfilerSettingEvent("Hotspots", String.valueOf(hotspotsEnabled)).commit();
      new ProfilerSettingEvent("Endpoints", String.valueOf(endpointsEnabled)).commit();
      new ProfilerSettingEvent("Auxiliary Profiler", auxiliaryProfiler).commit();
      new ProfilerSettingEvent("perf_events_paranoid", perfEventsParanoid).commit();
      new ProfilerSettingEvent("Native Stacks", String.valueOf(hasNativeStacks)).commit();
    }
  }
}
