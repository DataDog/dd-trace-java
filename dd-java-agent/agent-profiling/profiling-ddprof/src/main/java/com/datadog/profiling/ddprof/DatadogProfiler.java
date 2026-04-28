package com.datadog.profiling.ddprof;

import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getAllocationInterval;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getCStack;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getContextAttributes;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getCpuInterval;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getLiveHeapSamplePercent;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getLogLevel;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getSafeMode;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getSchedulingEvent;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getSchedulingEventInterval;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getStackDepth;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getWallCollapsing;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getWallContextFilter;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getWallInterval;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isAllocationProfilingEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isCpuProfilerEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isLiveHeapSizeTrackingEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isMemoryLeakProfilingEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isResourceNameContextAttributeEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isSpanNameContextAttributeEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isTrackingGenerations;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isWallClockProfilerEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.omitLineNumbers;
import static com.datadog.profiling.utils.ProfilingMode.ALLOCATION;
import static com.datadog.profiling.utils.ProfilingMode.CPU;
import static com.datadog.profiling.utils.ProfilingMode.MEMLEAK;
import static com.datadog.profiling.utils.ProfilingMode.WALL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DETAILED_DEBUG_LOGGING;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DETAILED_DEBUG_LOGGING_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS_DEFAULT;

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.utils.ProfilingMode;
import com.datadoghq.profiler.ContextSetter;
import com.datadoghq.profiler.JavaProfiler;
import datadog.environment.JavaVirtualMachine;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.TaskWrapper;
import datadog.trace.util.TempLocationManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * It is currently assumed that this class can be initialised early so that Datadog profiler's
 * thread filter captures all tracing activity, which means it must not be modified to depend on
 * JFR, so that it can be installed before tracing starts.
 */
public final class DatadogProfiler {
  private static final Logger log = LoggerFactory.getLogger(DatadogProfiler.class);

  /**
   * Extended {@link JavaProfiler} APIs that exist in newer ddprof builds but are absent from
   * older published jars (e.g. 1.40.0). When a method is null, {@link DatadogProfiler} no-ops or
   * falls back so we still compile and run against the minimum supported ddprof.
   */
  private static final Method RECORD_TRACE_ROOT_EXTENDED = optionalMethod(
      "recordTraceRoot",
      long.class,
      long.class,
      long.class,
      String.class,
      String.class,
      int.class);

  private static final Method RECORD_TASK_BLOCK = optionalMethod(
      "recordTaskBlock", long.class, long.class, long.class, long.class, long.class, long.class);

  private static final Method PARK_ENTER = optionalMethod("parkEnter", long.class, long.class);

  private static final Method PARK_EXIT = optionalMethod("parkExit", long.class, long.class);

  private static final Method RECORD_SPAN_NODE = optionalMethod(
      "recordSpanNode",
      long.class,
      long.class,
      long.class,
      long.class,
      long.class,
      int.class,
      int.class);

  /**
   * Oldest API: 7 args, no {@code submittingSpanId} (e.g. ddprof 1.40). Not present in 1.41+; use
   * {@link #RECORD_QUEUE_TIME_8} with {@code 0L} when this is null.
   */
  private static final Method RECORD_QUEUE_TIME_7 = optionalMethod(
      "recordQueueTime",
      long.class,
      long.class,
      Class.class,
      Class.class,
      Class.class,
      int.class,
      Thread.class);

  /** (startTicks, endTicks, task, scheduler, queueType, queueLength, origin, submittingSpanId) */
  private static final Method RECORD_QUEUE_TIME_8 = optionalMethod(
      "recordQueueTime",
      long.class,
      long.class,
      Class.class,
      Class.class,
      Class.class,
      int.class,
      Thread.class,
      long.class);

  /** submit + optional consuming override (newer ddprof) */
  private static final Method RECORD_QUEUE_TIME_9 = optionalMethod(
      "recordQueueTime",
      long.class,
      long.class,
      Class.class,
      Class.class,
      Class.class,
      int.class,
      Thread.class,
      long.class,
      long.class);

  /**
   * Not on all published ddprof API surfaces; must be invoked reflectively for compile against
   * minimum ddprof, then work at runtime when a newer native jar is loaded.
   */
  private static final Method SET_CONTEXT_VALUE = optionalMethod("setContextValue", int.class, int.class);

  private static final Method CONTEXT_SETTER_SET_INT_INT =
      optionalContextSetterMethod("setContextValue", int.class, int.class);

  private static Method optionalMethod(String name, Class<?>... paramTypes) {
    try {
      return JavaProfiler.class.getMethod(name, paramTypes);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static final Method CONTEXT_SETTER_ENCODE = optionalContextSetterMethod("encode", String.class);

  private static final Method REGISTER_CONSTANT = optionalProfilerDeclared("registerConstant", String.class);

  private static Method optionalContextSetterMethod(String name, Class<?>... paramTypes) {
    try {
      return ContextSetter.class.getMethod(name, paramTypes);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  /**
   * Package-private in {@link JavaProfiler}; used when {@link ContextSetter} has no public {@code
   * encode} (e.g. minimal or older ddprof).
   */
  private static Method optionalProfilerDeclared(String name, Class<?>... paramTypes) {
    try {
      Method m = JavaProfiler.class.getDeclaredMethod(name, paramTypes);
      m.setAccessible(true);
      return m;
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static final int[] EMPTY = new int[0];

  private static final String OPERATION = "_dd.trace.operation";
  private static final String RESOURCE = "_dd.trace.resource";

  private static final int MAX_NUM_ENDPOINTS = 8192;

  private final boolean detailedDebugLogging;

  /**
   * Creates a profiler API with default configuration, may result in loading the profiler native
   * library if that has not already happened, but this will not happen more than once. Applying
   * default configuration will not prevent overloading any setting that would not require reloading
   * the native library.
   *
   * @return a profiler with default configuration.
   */
  public static DatadogProfiler newInstance() {
    return newInstance(ConfigProvider.getInstance());
  }

  /**
   * Creates a new instance of the Datadog profiler API, may result in loading the profiler native
   * library if that has not already happened, but this will not happen more than once. The
   * underlying configuration for where to load the library from cannot be overridden by providing
   * config here, but all other properties can be changed.
   *
   * @param configProvider config
   * @return a profiler with the configuration applied.
   */
  public static DatadogProfiler newInstance(ConfigProvider configProvider) {
    return new DatadogProfiler(configProvider);
  }

  private final AtomicBoolean recordingFlag = new AtomicBoolean(false);
  private final ConfigProvider configProvider;
  private final JavaProfiler profiler;
  private final Set<ProfilingMode> profilingModes = EnumSet.noneOf(ProfilingMode.class);

  private final ContextSetter contextSetter;

  private final List<String> orderedContextAttributes;

  private final long queueTimeThresholdMillis;

  private final Path recordingsPath;

  private DatadogProfiler(ConfigProvider configProvider) {
    this(configProvider, getContextAttributes(configProvider));
  }

  // visible for testing
  DatadogProfiler(ConfigProvider configProvider, Set<String> contextAttributes) {
    this.configProvider = configProvider;
    this.profiler = DdprofLibraryLoader.javaProfiler().getComponent();
    this.detailedDebugLogging =
        configProvider.getBoolean(
            PROFILING_DETAILED_DEBUG_LOGGING, PROFILING_DETAILED_DEBUG_LOGGING_DEFAULT);
    Throwable reasonNotLoaded = DdprofLibraryLoader.javaProfiler().getReasonNotLoaded();
    if (reasonNotLoaded != null) {
      throw new UnsupportedOperationException(
          "Unable to instantiate datadog profiler", reasonNotLoaded);
    }

    // TODO enable/disable events by name (e.g. datadog.ExecutionSample), not flag, so configuration
    //  can be consistent with JFR event control
    if (isAllocationProfilingEnabled(configProvider)) {
      profilingModes.add(ALLOCATION);
    }
    if (isMemoryLeakProfilingEnabled(configProvider)) {
      profilingModes.add(MEMLEAK);
    }
    if (isCpuProfilerEnabled(configProvider)) {
      profilingModes.add(CPU);
    }
    if (isWallClockProfilerEnabled(configProvider)) {
      profilingModes.add(WALL);
    }
    this.orderedContextAttributes = new ArrayList<>(contextAttributes);
    if (isSpanNameContextAttributeEnabled(configProvider)) {
      orderedContextAttributes.add(OPERATION);
    }
    if (isResourceNameContextAttributeEnabled(configProvider)) {
      orderedContextAttributes.add(RESOURCE);
    }
    this.contextSetter = new ContextSetter(profiler, orderedContextAttributes);
    this.queueTimeThresholdMillis =
        configProvider.getLong(
            PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS,
            PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS_DEFAULT);

    this.recordingsPath = TempLocationManager.getInstance().getTempDir().resolve("recordings");
    if (!Files.exists(recordingsPath)) {
      try {
        Files.createDirectories(
            recordingsPath,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      } catch (IOException e) {
        log.warn("Failed to create recordings directory: {}", recordingsPath, e);
        throw new IllegalStateException(
            "Failed to create recordings directory: " + recordingsPath, e);
      }
    }
  }

  void addThread() {
    profiler.addThread();
  }

  void removeThread() {
    profiler.removeThread();
  }

  public String getVersion() {
    return profiler.getVersion();
  }

  @Nullable
  public OngoingRecording start() {
    log.debug("Starting profiling");
    try {
      return new DatadogProfilerRecording(this);
    } catch (IOException | IllegalStateException e) {
      log.debug("Failed to start Datadog profiler recording", e);
      return null;
    }
  }

  @Nullable
  public RecordingData stop(OngoingRecording recording) {
    log.debug("Stopping profiling");
    return recording.stop();
  }

  /** A call-back from {@linkplain DatadogProfilerRecording#stop()} */
  void stopProfiler() {
    if (recordingFlag.compareAndSet(true, false)) {
      profiler.stop();
      if (isActive()) {
        log.debug("Profiling is still active. Waiting to stop.");
        while (isActive()) {
          LockSupport.parkNanos(10_000_000L);
        }
      }
    }
  }

  public Set<ProfilingMode> enabledModes() {
    return profilingModes;
  }

  public boolean isActive() {
    try {
      String status = executeProfilerCmd("status");
      log.debug("Datadog Profiler Status = {}", status);
      return !status.contains("not active");
    } catch (IOException ignored) {
    }
    return false;
  }

  String executeProfilerCmd(String cmd) throws IOException {
    return profiler.execute(cmd);
  }

  Path newRecording() throws IOException, IllegalStateException {
    if (recordingFlag.compareAndSet(false, true)) {
      Path recFile = Files.createTempFile(recordingsPath, "dd-profiler-", ".jfr");
      String cmd = cmdStartProfiling(recFile);
      try {
        String rslt = executeProfilerCmd(cmd);
        log.debug("DatadogProfiler.execute({}) = {}", cmd, rslt);
      } catch (IOException | IllegalStateException e) {
        if (log.isDebugEnabled()) {
          log.warn("Unable to start Datadog profiler recording", e);
        } else {
          log.warn("Unable to start Datadog profiler recording: {}", e.getMessage());
        }
        recordingFlag.set(false);
        throw e;
      }
      return recFile;
    }
    throw new IllegalStateException("Datadog profiler session has already been started");
  }

  void dump(Path path) {
    profiler.dump(path);
  }

  String cmdStartProfiling(Path file) throws IllegalStateException {
    // 'start' = start, 'jfr=7' = store in JFR format ready for concatenation
    int safemode = getSafeMode(configProvider);
    if (safemode != ProfilingConfig.PROFILING_DATADOG_PROFILER_SAFEMODE_DEFAULT) {
      // be very vocal about messing around with the profiler safemode as it may induce crashes
      log.warn(
          "Datadog profiler safemode is enabled with overridden value {}. "
              + "This is not recommended and may cause instability and crashes.",
          safemode);
    }
    StringBuilder cmd = new StringBuilder("start,jfr");
    cmd.append(",file=").append(file.toAbsolutePath());
    cmd.append(",loglevel=").append(getLogLevel(configProvider));
    cmd.append(",jstackdepth=").append(getStackDepth(configProvider));
    cmd.append(",cstack=").append(getCStack(configProvider));
    cmd.append(",safemode=").append(getSafeMode(configProvider));
    cmd.append(",attributes=").append(String.join(";", orderedContextAttributes));
    cmd.append(",generations=").append(isTrackingGenerations(configProvider));
    if (omitLineNumbers(configProvider)) {
      cmd.append(",linenumbers=f");
    }
    if (profilingModes.contains(CPU)) {
      // cpu profiling is enabled.
      String schedulingEvent = getSchedulingEvent(configProvider);
      if (schedulingEvent != null && !schedulingEvent.isEmpty()) {
        // using a user-specified event, e.g. L1-dcache-load-misses
        cmd.append(",event=").append(schedulingEvent);
        int interval = getSchedulingEventInterval(configProvider);
        if (interval > 0) {
          cmd.append(",interval=").append(interval);
        }
      } else {
        // using cpu time schedule
        int interval = getCpuInterval();
        if (JavaVirtualMachine.isJ9())
          interval =
              interval == ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_INTERVAL_DEFAULT
                  ? ProfilingConfig.PROFILING_DATADOG_PROFILER_J9_CPU_INTERVAL_DEFAULT
                  : interval;
        cmd.append(",cpu=").append(interval).append('m');
      }
    }
    if (profilingModes.contains(WALL)) {
      // wall profiling is enabled.
      cmd.append(",wall=");
      if (getWallCollapsing(configProvider)) {
        cmd.append('~');
      }
      cmd.append(getWallInterval(configProvider)).append('m');
      // ddprof quirk: if filter parameter is omitted, it defaults to "0" (enabled),
      // not empty string (disabled). When enabled without tracing, no threads are added
      // to the filter, resulting in zero samples. We must explicitly pass filter= (empty)
      // to disable filtering and sample all threads.
      if (getWallContextFilter(configProvider)) {
        cmd.append(",filter=0");
      } else {
        cmd.append(",filter=");
      }
    }
    cmd.append(",loglevel=").append(getLogLevel(configProvider));
    if (profilingModes.contains(ALLOCATION) || profilingModes.contains(MEMLEAK)) {
      // allocation profiling or live heap profiling is enabled
      cmd.append(",memory=").append(getAllocationInterval(configProvider)).append('b');
      cmd.append(':');
      if (profilingModes.contains(ALLOCATION)) {
        cmd.append('a');
      }
      if (profilingModes.contains(MEMLEAK)) {
        cmd.append(isLiveHeapSizeTrackingEnabled(configProvider) ? 'L' : 'l');
        cmd.append(':')
            .append(String.format("%.2f", getLiveHeapSamplePercent(configProvider) / 100.0d));
      }
    }
    String cmdString = cmd.toString();
    log.debug("Datadog profiler command line: {}", cmdString);
    return cmdString;
  }

  public void recordTraceRoot(
      long rootSpanId, long parentSpanId, long startTicks, String endpoint, String operation) {
    if (RECORD_TRACE_ROOT_EXTENDED != null) {
      try {
        if (!(boolean)
            RECORD_TRACE_ROOT_EXTENDED.invoke(
                profiler,
                rootSpanId,
                parentSpanId,
                startTicks,
                endpoint,
                operation,
                MAX_NUM_ENDPOINTS)) {
          logEndpointLimit();
        }
        return;
      } catch (InvocationTargetException | IllegalAccessException e) {
        if (detailedDebugLogging) {
          log.debug("recordTraceRoot extended API failed, using legacy signature", e);
        }
      }
    }
    if (!profiler.recordTraceRoot(rootSpanId, endpoint, operation, MAX_NUM_ENDPOINTS)) {
      logEndpointLimit();
    }
  }

  private void logEndpointLimit() {
    log.debug(
        "Endpoint event not written because more than {} distinct endpoints have been encountered."
            + " This avoids excessive memory overhead.",
        MAX_NUM_ENDPOINTS);
  }

  public long getCurrentTicks() {
    return profiler.getCurrentTicks();
  }

  public int operationNameOffset() {
    return offsetOf(OPERATION);
  }

  public int resourceNameOffset() {
    return offsetOf(RESOURCE);
  }

  public int offsetOf(String attribute) {
    return contextSetter.offsetOf(attribute);
  }

  @SuppressWarnings("deprecation")
  public void setSpanContext(long spanId, long rootSpanId) {
    debugLogging(rootSpanId);
    try {
      profiler.setContext(spanId, rootSpanId);
    } catch (Throwable e) {
      log.debug("Failed to clear context", e);
    }
  }

  public void clearSpanContext() {
    debugLogging(0L);
    try {
      profiler.clearContext();
    } catch (Throwable e) {
      log.debug("Failed to set context", e);
    }
  }

  public boolean setContextValue(int offset, int encoding) {
    if (offset < 0) {
      return false;
    }
    if (SET_CONTEXT_VALUE != null) {
      try {
        SET_CONTEXT_VALUE.invoke(profiler, offset, encoding);
        return true;
      } catch (InvocationTargetException | IllegalAccessException e) {
        if (detailedDebugLogging) {
          log.debug("JavaProfiler.setContextValue failed", e);
        }
        return false;
      }
    }
    if (contextSetter != null && CONTEXT_SETTER_SET_INT_INT != null) {
      try {
        return (Boolean) CONTEXT_SETTER_SET_INT_INT.invoke(contextSetter, offset, encoding);
      } catch (InvocationTargetException | IllegalAccessException e) {
        if (detailedDebugLogging) {
          log.debug("ContextSetter.setContextValue(int,int) failed", e);
        }
      }
    }
    return false;
  }

  public boolean setContextValue(int offset, CharSequence value) {
    if (contextSetter != null && offset >= 0) {
      try {
        return contextSetter.setContextValue(
            offset, value != null ? value.toString() : null);
      } catch (Throwable e) {
        log.debug("Failed to set context", e);
      }
    }
    return false;
  }

  public boolean setContextValue(String attribute, CharSequence value) {
    if (contextSetter != null) {
      return setContextValue(contextSetter.offsetOf(attribute), value);
    }
    return false;
  }

  public boolean clearContextValue(String attribute) {
    if (contextSetter != null) {
      return clearContextValue(contextSetter.offsetOf(attribute));
    }
    return false;
  }

  public boolean clearContextValue(int offset) {
    if (contextSetter != null && offset >= 0) {
      try {
        return contextSetter.clearContextValue(offset);
      } catch (Throwable t) {
        log.debug("Failed to clear context", t);
      }
    }
    return false;
  }

  private void debugLogging(long localRootSpanId) {
    if (detailedDebugLogging && log.isDebugEnabled()) {
      log.debug("localRootSpanId={}", localRootSpanId, new Throwable());
    }
  }

  int encode(CharSequence constant) {
    if (constant == null || profiler == null) {
      return 0;
    }
    String s = constant.toString();
    if (contextSetter != null && CONTEXT_SETTER_ENCODE != null) {
      try {
        return (Integer) CONTEXT_SETTER_ENCODE.invoke(contextSetter, s);
      } catch (InvocationTargetException | IllegalAccessException e) {
        if (detailedDebugLogging) {
          log.debug("ContextSetter.encode failed", e);
        }
      }
    }
    if (REGISTER_CONSTANT != null) {
      try {
        return (Integer) REGISTER_CONSTANT.invoke(profiler, s);
      } catch (InvocationTargetException | IllegalAccessException e) {
        if (detailedDebugLogging) {
          log.debug("registerConstant failed", e);
        }
      }
    }
    return 0;
  }

  public int[] snapshot() {
    if (contextSetter != null) {
      return contextSetter.snapshotTags();
    }
    return EMPTY;
  }

  public void recordSetting(String name, String value) {
    profiler.recordSetting(name, value);
  }

  public void recordSetting(String name, String value, String unit) {
    profiler.recordSetting(name, value, unit);
  }

  public QueueTimeTracker newQueueTimeTracker(long submittingSpanId) {
    return new QueueTimeTracker(this, profiler.getCurrentTicks(), submittingSpanId);
  }

  boolean shouldRecordQueueTimeEvent(long startMillis) {
    return System.currentTimeMillis() - startMillis >= queueTimeThresholdMillis;
  }

  void recordTaskBlockEvent(
      long startTicks, long spanId, long rootSpanId, long blocker, long unblockingSpanId) {
    if (profiler == null || RECORD_TASK_BLOCK == null) {
      return;
    }
    long endTicks = profiler.getCurrentTicks();
    try {
      RECORD_TASK_BLOCK.invoke(
          profiler, startTicks, endTicks, spanId, rootSpanId, blocker, unblockingSpanId);
    } catch (InvocationTargetException | IllegalAccessException e) {
      if (detailedDebugLogging) {
        log.debug("recordTaskBlock failed", e);
      }
    }
  }

  void parkEnter(long spanId, long rootSpanId) {
    if (profiler == null || PARK_ENTER == null) {
      return;
    }
    try {
      PARK_ENTER.invoke(profiler, spanId, rootSpanId);
    } catch (InvocationTargetException | IllegalAccessException e) {
      if (detailedDebugLogging) {
        log.debug("parkEnter failed", e);
      }
    }
  }

  void parkExit(long blocker, long unblockingSpanId) {
    if (profiler == null || PARK_EXIT == null) {
      return;
    }
    try {
      PARK_EXIT.invoke(profiler, blocker, unblockingSpanId);
    } catch (InvocationTargetException | IllegalAccessException e) {
      if (detailedDebugLogging) {
        log.debug("parkExit failed", e);
      }
    }
  }

  public void recordSpanNodeEvent(
      long spanId,
      long parentSpanId,
      long rootSpanId,
      long startNanos,
      long durationNanos,
      int encodedOperation,
      int encodedResource) {
    if (profiler == null || RECORD_SPAN_NODE == null) {
      return;
    }
    try {
      RECORD_SPAN_NODE.invoke(
          profiler,
          spanId,
          parentSpanId,
          rootSpanId,
          startNanos,
          durationNanos,
          encodedOperation,
          encodedResource);
    } catch (InvocationTargetException | IllegalAccessException e) {
      if (detailedDebugLogging) {
        log.debug("recordSpanNode failed", e);
      }
    }
  }

  void recordQueueTimeEvent(
      long startTicks,
      Object task,
      Class<?> scheduler,
      Class<?> queueType,
      int queueLength,
      Thread origin,
      long submittingSpanId,
      long consumingSpanIdOverride) {
    if (profiler != null) {
      // note: because this type traversal can update secondary_super_cache (see JDK-8180450)
      // we avoid doing this unless we are absolutely certain we will record the event
      Class<?> taskType = TaskWrapper.getUnwrappedType(task);
      if (taskType != null) {
        long endTicks = profiler.getCurrentTicks();
        if (consumingSpanIdOverride != 0L && RECORD_QUEUE_TIME_9 != null) {
          try {
            RECORD_QUEUE_TIME_9.invoke(
                profiler,
                startTicks,
                endTicks,
                taskType,
                scheduler,
                queueType,
                queueLength,
                origin,
                submittingSpanId,
                consumingSpanIdOverride);
            return;
          } catch (InvocationTargetException | IllegalAccessException e) {
            if (detailedDebugLogging) {
              log.debug("recordQueueTime with consumingSpanIdOverride failed, trying 8-arg", e);
            }
          }
        }
        if (RECORD_QUEUE_TIME_8 != null) {
          try {
            RECORD_QUEUE_TIME_8.invoke(
                profiler,
                startTicks,
                endTicks,
                taskType,
                scheduler,
                queueType,
                queueLength,
                origin,
                submittingSpanId);
            return;
          } catch (InvocationTargetException | IllegalAccessException e) {
            if (detailedDebugLogging) {
              log.debug("recordQueueTime 8-arg failed, trying 7- or 8-arg fallback", e);
            }
          }
        }
        if (RECORD_QUEUE_TIME_7 != null) {
          try {
            RECORD_QUEUE_TIME_7.invoke(
                profiler, startTicks, endTicks, taskType, scheduler, queueType, queueLength, origin);
            return;
          } catch (InvocationTargetException | IllegalAccessException e) {
            if (detailedDebugLogging) {
              log.debug("recordQueueTime 7-arg failed", e);
            }
          }
        }
        if (RECORD_QUEUE_TIME_8 != null) {
          try {
            RECORD_QUEUE_TIME_8.invoke(
                profiler,
                startTicks,
                endTicks,
                taskType,
                scheduler,
                queueType,
                queueLength,
                origin,
                0L);
          } catch (InvocationTargetException | IllegalAccessException e) {
            if (detailedDebugLogging) {
              log.debug("recordQueueTime 8-arg (submittingSpanId=0) failed", e);
            }
          }
        }
      }
    }
  }
}
