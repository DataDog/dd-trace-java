package com.datadog.profiling.ddprof;

import static datadog.trace.api.Platform.isJ9;
import static datadog.trace.api.config.ProfilingConfig.*;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.lang.management.ManagementFactory;

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
    return getBoolean(
        configProvider,
        PROFILING_DATADOG_PROFILER_WALL_ENABLED,
        !isJ9() && PROFILING_DATADOG_PROFILER_WALL_ENABLED_DEFAULT);
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

  public static boolean isAllocationProfilingEnabled(ConfigProvider configProvider) {
    return getBoolean(
        configProvider,
        PROFILING_DATADOG_PROFILER_ALLOC_ENABLED,
        PROFILING_DATADOG_PROFILER_ALLOC_ENABLED_DEFAULT);
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
        PROFILING_DATADOG_PROFILER_MEMLEAK_ENABLED,
        PROFILING_DATADOG_PROFILER_MEMLEAK_ENABLED_DEFAULT);
  }

  public static boolean isMemoryLeakProfilingEnabled() {
    return isMemoryLeakProfilingEnabled(ConfigProvider.getInstance());
  }

  public static long getMemleakInterval(ConfigProvider configProvider) {
    long maxheap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    long memleakIntervalDefault =
        maxheap <= 0 ? 1024 * 1024 : maxheap / Math.max(1, getMemleakCapacity());
    return getLong(
        configProvider, PROFILING_DATADOG_PROFILER_MEMLEAK_INTERVAL, memleakIntervalDefault);
  }

  public static long getMemleakInterval() {
    return getMemleakInterval(ConfigProvider.getInstance());
  }

  public static int getMemleakCapacity(ConfigProvider configProvider) {
    return clamp(
        0,
        // see https://github.com/DataDog/java-profiler/blob/main/src/memleakTracer.h
        8192,
        getInteger(
            configProvider,
            PROFILING_DATADOG_PROFILER_MEMLEAK_CAPACITY,
            PROFILING_DATADOG_PROFILER_MEMLEAK_CAPACITY_DEFAULT));
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

  public static String getString(ConfigProvider configProvider, String key, String defaultValue) {
    return configProvider.getString(key, configProvider.getString(normalizeKey(key), defaultValue));
  }

  public static String getString(ConfigProvider configProvider, String key) {
    return configProvider.getString(key, configProvider.getString(normalizeKey(key)));
  }

  public static boolean getBoolean(
      ConfigProvider configProvider, String key, boolean defaultValue) {
    return configProvider.getBoolean(
        key, configProvider.getBoolean(normalizeKey(key), defaultValue));
  }

  public static boolean getBoolean(ConfigProvider configProvider, String key) {
    return configProvider.getBoolean(key, configProvider.getBoolean(normalizeKey(key), false));
  }

  public static int getInteger(ConfigProvider configProvider, String key, int defaultValue) {
    return configProvider.getInteger(
        key, configProvider.getInteger(normalizeKey(key), defaultValue));
  }

  public static int getInteger(ConfigProvider configProvider, String key) {
    return configProvider.getInteger(key, configProvider.getInteger(normalizeKey(key), -1));
  }

  public static long getLong(ConfigProvider configProvider, String key, long defaultValue) {
    return configProvider.getLong(key, configProvider.getLong(normalizeKey(key), defaultValue));
  }

  public static long getLong(ConfigProvider configProvider, String key) {
    return configProvider.getLong(key, configProvider.getLong(normalizeKey(key), -1));
  }

  private static String normalizeKey(String key) {
    return key.replace(".ddprof.", ".async.");
  }
}
