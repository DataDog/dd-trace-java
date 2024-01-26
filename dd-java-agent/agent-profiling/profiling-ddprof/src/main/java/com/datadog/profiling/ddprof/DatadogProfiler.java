package com.datadog.profiling.ddprof;

import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getAllocationInterval;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getCStack;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getContextAttributes;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getCpuInterval;
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
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isWallClockProfilerEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.omitLineNumbers;
import static com.datadog.profiling.utils.ProfilingMode.ALLOCATION;
import static com.datadog.profiling.utils.ProfilingMode.CPU;
import static com.datadog.profiling.utils.ProfilingMode.MEMLEAK;
import static com.datadog.profiling.utils.ProfilingMode.WALL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS_DEFAULT;

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.utils.ProfilingMode;
import com.datadoghq.profiler.ContextSetter;
import com.datadoghq.profiler.JavaProfiler;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

  private static final int[] EMPTY = new int[0];

  private static final String OPERATION = "_dd.trace.operation";
  private static final String RESOURCE = "_dd.trace.resource";

  private static final int MAX_NUM_ENDPOINTS = 8192;

  private static final class Singleton {
    private static final DatadogProfiler INSTANCE = newInstance();
  }

  private static void logFailedInstantiation(Throwable error) {
    if (Platform.isNativeImageBuilder() || Platform.isNativeImage()) {
      // Datadog profiler is not supported in native-image mode
      // Skip logging the error
      return;
    }
    OperatingSystem os = OperatingSystem.current();
    if (os != OperatingSystem.linux) {
      log.debug("Datadog profiler only supported on Linux", error);
    } else if (log.isDebugEnabled()) {
      log.warn(
          String.format("failed to instantiate Datadog profiler on %s %s", os, Arch.current()),
          error);
    } else {
      log.warn(
          "failed to instantiate Datadog profiler on {} {} because: {}",
          os,
          Arch.current(),
          error.getMessage());
    }
  }

  static DatadogProfiler newInstance() {
    DatadogProfiler instance = null;
    try {
      instance = new DatadogProfiler();
    } catch (Throwable t) {
      logFailedInstantiation(t);
      instance = new DatadogProfiler((Void) null);
    }
    return instance;
  }

  public static DatadogProfiler newInstance(ConfigProvider configProvider) {
    // do not recreate the default instance
    // it is ok to use identity check as we are requiring the exact same instance
    if (configProvider == Singleton.INSTANCE.configProvider) {
      return Singleton.INSTANCE;
    }

    DatadogProfiler instance = null;
    try {
      instance = new DatadogProfiler(configProvider);
    } catch (Throwable t) {
      logFailedInstantiation(t);
      instance = new DatadogProfiler((Void) null);
    }
    return instance;
  }

  private final AtomicBoolean recordingFlag = new AtomicBoolean(false);
  private final ConfigProvider configProvider;
  private final JavaProfiler profiler;
  private final Set<ProfilingMode> profilingModes = EnumSet.noneOf(ProfilingMode.class);

  private final ContextSetter contextSetter;

  private final List<String> orderedContextAttributes;

  private final long queueTimeThreshold;

  private DatadogProfiler() throws UnsupportedEnvironmentException {
    this(ConfigProvider.getInstance());
  }

  private DatadogProfiler(Void dummy) {
    this.configProvider = null;
    this.profiler = null;
    this.contextSetter = null;
    this.orderedContextAttributes = null;
    this.queueTimeThreshold = Long.MAX_VALUE;
  }

  private DatadogProfiler(ConfigProvider configProvider) throws UnsupportedEnvironmentException {
    this(configProvider, getContextAttributes(configProvider));
  }

  // visible for testing
  DatadogProfiler(ConfigProvider configProvider, Set<String> contextAttributes)
      throws UnsupportedEnvironmentException {
    this.configProvider = configProvider;
    try {
      profiler =
          JavaProfiler.getInstance(
              configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH),
              configProvider.getString(
                  ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH,
                  ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH_DEFAULT));
    } catch (IOException e) {
      throw new UnsupportedOperationException("Unable to instantiate datadog profiler", e);
    }
    if (profiler == null) {
      throw new UnsupportedEnvironmentException("Unable to instantiate datadog profiler");
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
    this.queueTimeThreshold =
        configProvider.getLong(
            PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS,
            PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS_DEFAULT);
    try {
      // sanity test - force load Datadog profiler to catch it not being available early
      profiler.execute("status");
    } catch (IOException e) {
      throw new UnsupportedEnvironmentException("Failed to execute status on Datadog profiler", e);
    }
  }

  public static DatadogProfiler getInstance() {
    return Singleton.INSTANCE;
  }

  void addThread() {
    if (profiler != null) {
      profiler.addThread();
    }
  }

  void removeThread() {
    if (profiler != null) {
      profiler.removeThread();
    }
  }

  public String getVersion() {
    if (profiler != null) {
      return profiler.getVersion();
    }
    return "profiler not loaded";
  }

  @Nullable
  public OngoingRecording start() {
    if (profiler != null) {
      log.debug("Starting profiling");
      try {
        return new DatadogProfilerRecording(this);
      } catch (IOException | IllegalStateException e) {
        log.debug("Failed to start Datadog profiler recording", e);
        return null;
      }
    }
    return null;
  }

  @Nullable
  public RecordingData stop(OngoingRecording recording) {
    if (profiler != null) {
      log.debug("Stopping profiling");
      return recording.stop();
    }
    return null;
  }

  /** A call-back from {@linkplain DatadogProfilerRecording#stop()} */
  void stopProfiler() {
    if (profiler != null) {
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
  }

  public Set<ProfilingMode> enabledModes() {
    return profilingModes;
  }

  public boolean isAvailable() {
    return profiler != null;
  }

  boolean isActive() {
    if (!isAvailable()) {
      return false;
    }
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
      Path recFile = Files.createTempFile("dd-profiler-", ".jfr");
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
    StringBuilder cmd = new StringBuilder("start,jfr=7");
    cmd.append(",file=").append(file.toAbsolutePath());
    cmd.append(",loglevel=").append(getLogLevel(configProvider));
    cmd.append(",jstackdepth=").append(getStackDepth(configProvider));
    cmd.append(",cstack=").append(getCStack(configProvider));
    cmd.append(",safemode=").append(getSafeMode(configProvider));
    cmd.append(",attributes=").append(String.join(";", orderedContextAttributes));
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
        cmd.append(",cpu=").append(getCpuInterval(configProvider)).append('m');
      }
    }
    if (profilingModes.contains(WALL)) {
      // wall profiling is enabled.
      cmd.append(",wall=");
      if (getWallCollapsing(configProvider)) {
        cmd.append("~");
      }
      cmd.append(getWallInterval(configProvider)).append('m');
      if (getWallContextFilter(configProvider)) {
        cmd.append(",filter=0");
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
      }
    }
    String cmdString = cmd.toString();
    log.debug("Datadog profiler command line: {}", cmdString);
    return cmdString;
  }

  public void recordTraceRoot(long rootSpanId, String endpoint, String operation) {
    if (profiler != null) {
      if (!profiler.recordTraceRoot(rootSpanId, endpoint, operation, MAX_NUM_ENDPOINTS)) {
        log.debug(
            "Endpoint event not written because more than {} distinct endpoints have been encountered."
                + " This avoids excessive memory overhead.",
            MAX_NUM_ENDPOINTS);
      }
    }
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

  public void setSpanContext(long spanId, long rootSpanId) {
    if (profiler != null) {
      try {
        profiler.setContext(spanId, rootSpanId);
      } catch (IllegalStateException e) {
        log.debug("Failed to clear context", e);
      }
    }
  }

  public void clearSpanContext() {
    if (profiler != null) {
      try {
        profiler.setContext(0L, 0L);
      } catch (IllegalStateException e) {
        log.debug("Failed to set context", e);
      }
    }
  }

  public boolean setContextValue(int offset, int encoding) {
    if (contextSetter != null && offset >= 0) {
      return contextSetter.setContextValue(offset, encoding);
    }
    return false;
  }

  public boolean setContextValue(int offset, CharSequence value) {
    if (contextSetter != null && offset >= 0) {
      int encoding = encode(value);
      return contextSetter.setContextValue(offset, encoding);
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
      return contextSetter.clearContextValue(offset);
    }
    return false;
  }

  int encode(CharSequence constant) {
    if (constant != null && profiler != null) {
      return contextSetter.encode(constant.toString());
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
    if (profiler != null) {
      profiler.recordSetting(name, value);
    }
  }

  public void recordSetting(String name, String value, String unit) {
    if (profiler != null) {
      profiler.recordSetting(name, value, unit);
    }
  }

  public QueueTimeTracker newQueueTimeTracker() {
    if (profiler != null) {
      return new QueueTimeTracker(profiler, queueTimeThreshold);
    }
    return null;
  }
}
