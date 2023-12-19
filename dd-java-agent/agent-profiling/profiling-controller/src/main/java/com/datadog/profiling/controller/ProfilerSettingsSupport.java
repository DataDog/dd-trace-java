package com.datadog.profiling.controller;

import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Capture the profiler config first and allow emitting the setting events per each recording. */
public abstract class ProfilerSettingsSupport {
  protected static final String JFR_IMPLEMENTATION_KEY = "JFR Implementation";
  protected static final String UPLOAD_PERIOD_KEY = "Upload Period";
  protected static final String UPLOAD_TIMEOUT_KEY = "Upload Timeout";
  protected static final String UPLOAD_COMPRESSION_KEY = "Upload Compression";
  protected static final String ALLOCATION_PROFILING_KEY = "Allocation Profiling";
  protected static final String HEAP_PROFILING_KEY = "Heap Profiling";
  protected static final String FORCE_START_FIRST_KEY = "Force Start-First";
  protected static final String HOTSPOTS_KEY = "Hotspots";
  protected static final String ENDPOINTS_KEY = "Endpoints";
  protected static final String AUXILIARY_PROFILER_KEY = "Auxiliary Profiler";
  protected static final String PERF_EVENTS_PARANOID_KEY = "perf_events_paranoid";
  protected static final String NATIVE_STACKS_KEY = "Native Stacks";
  protected static final String STACK_DEPTH_KEY = "Stack Depth";

  protected final int uploadPeriod;
  protected final int uploadTimeout;
  protected final String uploadCompression;
  protected final boolean allocationProfilingEnabled;
  protected final boolean heapProfilingEnabled;
  protected final boolean startForceFirst;
  protected final String templateOverride;
  protected final int exceptionSampleLimit;
  protected final int exceptionHistogramTopItems;
  protected final int exceptionHistogramMaxSize;
  protected final boolean hotspotsEnabled;
  protected final boolean endpointsEnabled;
  protected final String auxiliaryProfiler;
  protected final String perfEventsParanoid;
  protected final boolean hasNativeStacks;

  protected final int stackDepth;
  protected volatile boolean hasJfrStackDepthApplied = false;

  protected ProfilerSettingsSupport(ConfigProvider configProvider) {
    uploadPeriod =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_UPLOAD_PERIOD,
            ProfilingConfig.PROFILING_UPLOAD_PERIOD_DEFAULT);
    uploadTimeout =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_UPLOAD_TIMEOUT,
            ProfilingConfig.PROFILING_UPLOAD_TIMEOUT_DEFAULT);
    uploadCompression =
        configProvider.getString(
            ProfilingConfig.PROFILING_UPLOAD_COMPRESSION,
            ProfilingConfig.PROFILING_UPLOAD_COMPRESSION_DEFAULT);
    allocationProfilingEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_ALLOCATION_ENABLED,
            ProfilingSupport.isObjectAllocationSampleAvailable());
    heapProfilingEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_HEAP_ENABLED, ProfilingConfig.PROFILING_HEAP_ENABLED_DEFAULT);
    startForceFirst =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_START_FORCE_FIRST,
            ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT);
    templateOverride = configProvider.getString(ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE);
    exceptionSampleLimit =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT,
            ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT);
    exceptionHistogramTopItems =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS,
            ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT);
    exceptionHistogramMaxSize =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE,
            ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT);
    hotspotsEnabled = configProvider.getBoolean(ProfilingConfig.PROFILING_HOTSPOTS_ENABLED, false);
    endpointsEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED,
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT);
    auxiliaryProfiler =
        configProvider.getString(
            ProfilingConfig.PROFILING_AUXILIARY_TYPE, getDefaultAuxiliaryProfiler());
    perfEventsParanoid = readPerfEventsParanoidSetting();
    hasNativeStacks =
        !"no"
            .equalsIgnoreCase(
                configProvider.getString(
                    ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK,
                    configProvider.getString(
                        "profiling.async.cstack",
                        ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK_DEFAULT)));
    stackDepth =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_STACKDEPTH,
            ProfilingConfig.PROFILING_STACKDEPTH_DEFAULT,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_STACKDEPTH);
  }

  private static String getDefaultAuxiliaryProfiler() {
    return Config.get().isDatadogProfilerEnabled()
        ? "ddprof"
        : ProfilingConfig.PROFILING_AUXILIARY_TYPE_DEFAULT;
  }

  /** To be defined in controller specific way. Eg. one could emit JFR events. */
  public abstract void publish();

  public void markJfrStackDepthApplied() {
    hasJfrStackDepthApplied = true;
  }

  private static String readPerfEventsParanoidSetting() {
    String value = "unknown";
    if (Platform.isLinux()) {
      Path perfEventsParanoid = Paths.get("/proc/sys/kernel/perf_event_paranoid");
      try {
        if (Files.exists(perfEventsParanoid)) {
          value = new String(Files.readAllBytes(perfEventsParanoid), StandardCharsets.UTF_8).trim();
        }
      } catch (Exception ignore) {
      }
    }
    return value;
  }
}
