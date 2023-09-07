package com.datadog.profiling.ddprof;

import static datadog.trace.api.Platform.isJ9;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_INTERVAL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_INTERVAL_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_INTERVAL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_INTERVAL_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_CAPACITY;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_CAPACITY_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_INTERVAL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE_DEFAFULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LOG_LEVEL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LOG_LEVEL_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_MEMLEAK_CAPACITY;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_MEMLEAK_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_MEMLEAK_INTERVAL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_SAFEMODE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_SAFEMODE_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT_INTERVAL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_STACKDEPTH;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_STACKDEPTH_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_COLLAPSING;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_COLLAPSING_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ULTRA_MINIMAL;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Set;

public class DatadogProfilerConfig {

  public static boolean isCpuProfilerEnabled(ConfigProvider configProvider) {
    return getBoolean(
        configProvider,
        PROFILING_DATADOG_PROFILER_CPU_ENABLED,
        PROFILING_DATADOG_PROFILER_CPU_ENABLED_DEFAULT);
  }

  public static String getLibPath(ConfigProvider configProvider) {
    return getString(configProvider, PROFILING_DATADOG_PROFILER_LIBPATH);
  }

  public static String getLibPath() {
    return getLibPath(ConfigProvider.getInstance());
  }

  public static boolean isCpuProfilerEnabled() {
    return isCpuProfilerEnabled(ConfigProvider.getInstance());
  }

  public static int getCpuInterval(ConfigProvider configProvider) {
    return getInteger(
        configProvider,
        PROFILING_DATADOG_PROFILER_CPU_INTERVAL,
        PROFILING_DATADOG_PROFILER_CPU_INTERVAL_DEFAULT);
  }

  public static int getCpuInterval() {
    return getCpuInterval(ConfigProvider.getInstance());
  }

  public static String getSchedulingEvent(ConfigProvider configProvider) {
    return getString(configProvider, PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT);
  }

  public static String getSchedulingEvent() {
    return getSchedulingEvent(ConfigProvider.getInstance());
  }

  public static int getSchedulingEventInterval(ConfigProvider configProvider) {
    return getInteger(configProvider, PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT_INTERVAL);
  }

  public static int getSchedulingEventInterval() {
    return getSchedulingEventInterval(ConfigProvider.getInstance());
  }

  public static boolean isWallClockProfilerEnabled() {
    return isWallClockProfilerEnabled(ConfigProvider.getInstance());
  }

  public static boolean isWallClockProfilerEnabled(ConfigProvider configProvider) {
    boolean isUltraMinimal = getBoolean(configProvider, PROFILING_ULTRA_MINIMAL, false);
    boolean isTracingEnabled = configProvider.getBoolean(TRACE_ENABLED, true);
    boolean disableUnlessOptedIn = isUltraMinimal || !isTracingEnabled || isJ9();
    return getBoolean(
        configProvider, PROFILING_DATADOG_PROFILER_WALL_ENABLED, disableUnlessOptedIn);
  }

  public static int getWallInterval(ConfigProvider configProvider) {
    return getInteger(
        configProvider,
        PROFILING_DATADOG_PROFILER_WALL_INTERVAL,
        PROFILING_DATADOG_PROFILER_WALL_INTERVAL_DEFAULT);
  }

  public static int getWallInterval() {
    return getWallInterval(ConfigProvider.getInstance());
  }

  public static boolean getWallCollapsing(ConfigProvider configProvider) {
    return getBoolean(
        configProvider,
        PROFILING_DATADOG_PROFILER_WALL_COLLAPSING,
        PROFILING_DATADOG_PROFILER_WALL_COLLAPSING_DEFAULT);
  }

  public static boolean getWallContextFilter(ConfigProvider configProvider) {
    return getBoolean(
        configProvider,
        PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER,
        PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER_DEFAULT);
  }

  public static boolean isAllocationProfilingEnabled(ConfigProvider configProvider) {
    boolean userOptedIn =
        getBoolean(configProvider, PROFILING_DATADOG_PROFILER_ALLOC_ENABLED, false);
    // once DD allocation profiling is GA use the longstanding allocation flag to toggle it
    if (PROFILING_DATADOG_PROFILER_ALLOC_ENABLED_DEFAULT) {
      return getBoolean(configProvider, PROFILING_ALLOCATION_ENABLED, userOptedIn);
    }
    return userOptedIn;
  }

  public static boolean isAllocationProfilingEnabled() {
    return isAllocationProfilingEnabled(ConfigProvider.getInstance());
  }

  public static int getAllocationInterval(ConfigProvider configProvider) {
    return getInteger(
        configProvider,
        PROFILING_DATADOG_PROFILER_ALLOC_INTERVAL,
        PROFILING_DATADOG_PROFILER_ALLOC_INTERVAL_DEFAULT);
  }

  public static int getAllocationInterval() {
    return getAllocationInterval(ConfigProvider.getInstance());
  }

  public static boolean isMemoryLeakProfilingEnabled(ConfigProvider configProvider) {
    return getBoolean(
        configProvider,
        PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED,
        PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED_DEFAULT,
        PROFILING_DATADOG_PROFILER_MEMLEAK_ENABLED);
  }

  public static boolean isMemoryLeakProfilingEnabled() {
    return isMemoryLeakProfilingEnabled(ConfigProvider.getInstance());
  }

  public static boolean isLiveHeapSizeTrackingEnabled(ConfigProvider configProvider) {
    return getBoolean(
        configProvider,
        PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE,
        PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE_DEFAFULT);
  }

  public static long getMemleakInterval(ConfigProvider configProvider) {
    long maxheap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    long memleakIntervalDefault =
        maxheap <= 0 ? 1024 * 1024 : maxheap / Math.max(1, getMemleakCapacity());
    return getLong(
        configProvider,
        PROFILING_DATADOG_PROFILER_LIVEHEAP_INTERVAL,
        memleakIntervalDefault,
        PROFILING_DATADOG_PROFILER_MEMLEAK_INTERVAL);
  }

  public static long getMemleakInterval() {
    return getMemleakInterval(ConfigProvider.getInstance());
  }

  public static int getMemleakCapacity(ConfigProvider configProvider) {
    return clamp(
        0,
        // see
        // https://github.com/DataDog/java-profiler/blob/main/ddprof-lib/src/main/cpp/livenessTracker.h#L54
        8192,
        getInteger(
            configProvider,
            PROFILING_DATADOG_PROFILER_LIVEHEAP_CAPACITY,
            PROFILING_DATADOG_PROFILER_LIVEHEAP_CAPACITY_DEFAULT,
            PROFILING_DATADOG_PROFILER_MEMLEAK_CAPACITY));
  }

  public static int getMemleakCapacity() {
    return getMemleakCapacity(ConfigProvider.getInstance());
  }

  public static int getStackDepth(ConfigProvider configProvider) {
    return getInteger(
        configProvider,
        PROFILING_DATADOG_PROFILER_STACKDEPTH,
        PROFILING_DATADOG_PROFILER_STACKDEPTH_DEFAULT);
  }

  public static int getStackDepth() {
    return getStackDepth(ConfigProvider.getInstance());
  }

  public static int getSafeMode(ConfigProvider configProvider) {
    return getInteger(
        configProvider,
        PROFILING_DATADOG_PROFILER_SAFEMODE,
        PROFILING_DATADOG_PROFILER_SAFEMODE_DEFAULT);
  }

  public static int getSafeMode() {
    return getSafeMode(ConfigProvider.getInstance());
  }

  public static String getCStack(ConfigProvider configProvider) {
    return getString(
        configProvider,
        PROFILING_DATADOG_PROFILER_CSTACK,
        PROFILING_DATADOG_PROFILER_CSTACK_DEFAULT);
  }

  public static String getCStack() {
    return getCStack(ConfigProvider.getInstance());
  }

  private static int clamp(int min, int max, int value) {
    return Math.max(min, Math.min(max, value));
  }

  public static String getLogLevel(ConfigProvider configProvider) {
    return getString(
        configProvider,
        PROFILING_DATADOG_PROFILER_LOG_LEVEL,
        PROFILING_DATADOG_PROFILER_LOG_LEVEL_DEFAULT);
  }

  public static String getLogLevel() {
    return getLogLevel(ConfigProvider.getInstance());
  }

  public static Set<String> getContextAttributes(ConfigProvider configProvider) {
    return configProvider.getSet(PROFILING_CONTEXT_ATTRIBUTES, Collections.emptySet());
  }

  public static boolean isQueueingTimeEnabled() {
    return isQueueingTimeEnabled(ConfigProvider.getInstance());
  }

  public static boolean isQueueingTimeEnabled(ConfigProvider configProvider) {
    return configProvider.getBoolean(
        PROFILING_QUEUEING_TIME_ENABLED, PROFILING_QUEUEING_TIME_ENABLED_DEFAULT);
  }

  public static boolean isSpanNameContextAttributeEnabled() {
    return isSpanNameContextAttributeEnabled(ConfigProvider.getInstance());
  }

  public static boolean isSpanNameContextAttributeEnabled(ConfigProvider configProvider) {
    return configProvider.getBoolean(PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED, true);
  }

  public static String getString(ConfigProvider configProvider, String key, String defaultValue) {
    return configProvider.getString(key, configProvider.getString(normalizeKey(key), defaultValue));
  }

  public static String getString(ConfigProvider configProvider, String key) {
    return configProvider.getString(key, configProvider.getString(normalizeKey(key)));
  }

  public static boolean getBoolean(
      ConfigProvider configProvider, String key, boolean defaultValue, String... aliases) {
    return configProvider.getBoolean(
        key, configProvider.getBoolean(normalizeKey(key), defaultValue), aliases);
  }

  public static boolean getBoolean(ConfigProvider configProvider, String key) {
    return configProvider.getBoolean(key, configProvider.getBoolean(normalizeKey(key), false));
  }

  public static int getInteger(
      ConfigProvider configProvider, String key, int defaultValue, String... aliases) {
    return configProvider.getInteger(
        key, configProvider.getInteger(normalizeKey(key), defaultValue), aliases);
  }

  public static int getInteger(ConfigProvider configProvider, String key) {
    return configProvider.getInteger(key, configProvider.getInteger(normalizeKey(key), -1));
  }

  public static long getLong(
      ConfigProvider configProvider, String key, long defaultValue, String... aliases) {
    return configProvider.getLong(
        key, configProvider.getLong(normalizeKey(key), defaultValue), aliases);
  }

  public static long getLong(ConfigProvider configProvider, String key) {
    return configProvider.getLong(key, configProvider.getLong(normalizeKey(key), -1));
  }

  private static String normalizeKey(String key) {
    return key.replace(".ddprof.", ".async.");
  }
}
