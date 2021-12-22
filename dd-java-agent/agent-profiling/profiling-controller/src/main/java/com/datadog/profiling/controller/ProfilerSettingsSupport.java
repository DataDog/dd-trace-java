package com.datadog.profiling.controller;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

/** Capture the profiler config first and allow emitting the setting events per each recording. */
public abstract class ProfilerSettingsSupport {
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
  protected final boolean checkpointsEnabled;
  protected final boolean endpointsEnabled;
  protected final String auxiliaryProfiler;

  protected ProfilerSettingsSupport() {
    ConfigProvider configProvider = ConfigProvider.getInstance();
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
            ProfilingConfig.PROFILING_ALLOCATION_ENABLED_DEFAULT);
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
    checkpointsEnabled =
        !configProvider.getBoolean(
            ProfilingConfig.PROFILING_LEGACY_TRACING_INTEGRATION,
            ProfilingConfig.PROFILING_LEGACY_TRACING_INTEGRATION_DEFAULT);
    endpointsEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED,
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT);
    auxiliaryProfiler =
        configProvider.getString(
            ProfilingConfig.PROFILING_AUXILIARY_TYPE,
            ProfilingConfig.PROFILING_AUXILIARY_TYPE_DEFAULT);
  }

  /** To be defined in controller specific way. Eg. one could emit JFR events. */
  public abstract void publish();
}
