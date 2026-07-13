package com.datadog.profiling.ddprof;

import static com.datadog.profiling.ddprof.DatadogProfilerConfig.enableJMethodIDOptim;
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
import java.util.ArrayList;
import java.util.Arrays;
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

  // True for each attribute slot that was configured by the application (e.g. foo, bar).
  // ddprof wipes all custom slots on setContext; these slots are re-applied via
  // reapplyAppContext() on span activation.
  private final boolean[] isAppOffset;

  private final boolean hasAppContext;

  /**
   * Per-thread snapshot of application attribute values. Lazily allocated; only threads that call
   * setContextValue for an app attribute ever allocate. Holds the String value for each slot; the
   * all-native reapply resolves each value's encoding through the process-wide value cache.
   */
  private final ThreadLocal<AppContextSnapshot> appContextValues = new ThreadLocal<>();

  private final ThreadLocal<ScopeStack> scopeStack = new ThreadLocal<>();

  /** Per-thread stack of pre-allocated save slots for {@link DatadogProfilingScope}. */
  static final class ScopeStack {
    private final int attrCount;
    AppContextSnapshot[] slots;
    int depth;

    ScopeStack(int attrCount) {
      this.attrCount = attrCount;
      slots = new AppContextSnapshot[8];
      for (int i = 0; i < slots.length; i++) {
        slots[i] = new AppContextSnapshot(attrCount);
      }
    }

    AppContextSnapshot borrow() {
      if (depth >= slots.length) {
        AppContextSnapshot[] grown = new AppContextSnapshot[slots.length * 2];
        System.arraycopy(slots, 0, grown, 0, slots.length);
        // Eagerly fill the newly added slots so every index is non-null.
        for (int i = slots.length; i < grown.length; i++) {
          grown[i] = new AppContextSnapshot(attrCount);
        }
        slots = grown;
      }
      return slots[depth++];
    }

    void release() {
      if (depth > 0) slots[--depth].reset();
    }
  }

  // Package-private so DatadogProfilingScope can hold a typed reference for save/restore.
  static final class AppContextSnapshot {
    // App-managed attribute values, indexed by slot. On the all-native path a value is written via
    // JavaProfiler.setContextValue (which resolves it through the process-wide value cache), so the
    // snapshot only needs the String — no cached constant ID / UTF-8 bytes.
    private final String[] strings;
    // Count of slots with a non-null value; allows O(1) isEmpty().
    private int nonZeroCount;

    AppContextSnapshot(int size) {
      strings = new String[size];
    }

    void record(int offset, String value) {
      if (strings[offset] == null && value != null) {
        nonZeroCount++;
      } else if (strings[offset] != null && value == null) {
        nonZeroCount--;
      }
      strings[offset] = value;
    }

    void clear(int offset) {
      if (strings[offset] != null) nonZeroCount--;
      strings[offset] = null;
    }

    boolean isEmpty() {
      return nonZeroCount == 0;
    }

    int nonZeroCount() {
      return nonZeroCount;
    }

    String stringAt(int offset) {
      return strings[offset];
    }

    void copyFrom(AppContextSnapshot src) {
      System.arraycopy(src.strings, 0, strings, 0, strings.length);
      nonZeroCount = src.nonZeroCount;
    }

    void reset() {
      Arrays.fill(strings, null);
      nonZeroCount = 0;
    }
  }

  private final long queueTimeThresholdMillis;

  private final Path recordingsPath;

  private DatadogProfiler(ConfigProvider configProvider) {
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
    Set<String> contextAttributes = getContextAttributes(configProvider);
    this.orderedContextAttributes = getOrderedContextAttributes(contextAttributes, configProvider);
    this.contextSetter = new ContextSetter(profiler, orderedContextAttributes);
    // ContextSetter deduplicates and truncates to 10 internally; size arrays to its actual size.
    // size() is a pure-Java count (no DBB read) — kept alongside offsetOf as the only ContextSetter
    // methods this bridge still uses; all context writes/reads are all-native.
    int contextSize = contextSetter.size();
    boolean[] appOffsets = new boolean[contextSize];
    boolean anyApp = false;
    for (String attribute : contextAttributes) {
      int idx = contextSetter.offsetOf(attribute);
      if (idx >= 0) {
        appOffsets[idx] = true;
        anyApp = true;
      }
    }
    this.isAppOffset = appOffsets;
    this.hasAppContext = anyApp;
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

  /**
   * Computes the ordered context-attribute list (base attributes from config, then the optional
   * span-name and resource-name attributes) in the exact order used for the per-thread {@link
   * ContextSetter} and the native profiler's {@code attributes=} argument. Exposed so the OTel
   * process context can publish the same {@code attribute_key_map} before the profiler starts.
   */
  public static List<String> getOrderedContextAttributes(ConfigProvider configProvider) {
    return getOrderedContextAttributes(getContextAttributes(configProvider), configProvider);
  }

  private static List<String> getOrderedContextAttributes(
      Set<String> contextAttributes, ConfigProvider configProvider) {
    List<String> ordered = new ArrayList<>(contextAttributes);
    if (isSpanNameContextAttributeEnabled(configProvider)) {
      ordered.add(OPERATION);
    }
    if (isResourceNameContextAttributeEnabled(configProvider)) {
      ordered.add(RESOURCE);
    }
    return ordered;
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

    // Default is true
    if (enableJMethodIDOptim(configProvider)) {
      cmd.append(",fjmethodid=false");
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

  public void recordTraceRoot(long rootSpanId, String endpoint, String operation) {
    if (!profiler.recordTraceRoot(rootSpanId, endpoint, operation, MAX_NUM_ENDPOINTS)) {
      log.debug(
          "Endpoint event not written because more than {} distinct endpoints have been encountered."
              + " This avoids excessive memory overhead.",
          MAX_NUM_ENDPOINTS);
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

  /**
   * Combined per-activation write: full trace/span context plus the span-derived operation and
   * resource attributes, in a single native call, followed by a reapply of app-managed attributes
   * (the native call resets all custom slots). Replaces the previous {@code setContext} + two
   * {@code setContextValue} calls. A negative attribute offset (or null value) skips that
   * attribute.
   */
  public void setTraceContext(
      long rootSpanId,
      long spanId,
      long traceIdHigh,
      long traceIdLow,
      int operationOffset,
      CharSequence operationName,
      int resourceOffset,
      CharSequence resourceName) {
    if (spanId == 0) {
      // The native setTraceContext is the activation path and rejects a zero span with
      // IllegalArgumentException — which the catch below would swallow, leaving the previous
      // span's context stale on this thread. Span ids are non-zero by construction
      // (IdGenerationStrategy never returns 0; DDSpanId.ZERO means "no span"), so a zero here is
      // not expected; degrade to a clean clear rather than a silently-swallowed throw over stale
      // state.
      clearTraceContext();
      return;
    }
    debugLogging(rootSpanId);
    try {
      profiler.setTraceContext(
          rootSpanId,
          spanId,
          traceIdHigh,
          traceIdLow,
          operationOffset,
          operationName,
          resourceOffset,
          resourceName);
    } catch (Throwable e) {
      log.debug("Failed to set trace context", e);
    }
    reapplyAppContext();
  }

  /** Per-deactivation clear; reapplies app-managed attributes afterwards (see setTraceContext). */
  public void clearTraceContext() {
    debugLogging(0L);
    try {
      profiler.clearTraceContext();
    } catch (Throwable e) {
      log.debug("Failed to clear trace context", e);
    }
    reapplyAppContext();
  }

  public void setSpanContext(long rootSpanId, long spanId, long traceIdHigh, long traceIdLow) {
    setTraceContext(rootSpanId, spanId, traceIdHigh, traceIdLow, -1, null, -1, null);
  }

  public void clearSpanContext() {
    clearTraceContext();
  }

  public boolean setContextValue(int offset, String value) {
    if (offset >= 0 && value != null) {
      try {
        // Native call first; snapshot updated only on success so Java and ddprof state stay in
        // sync.
        if (profiler.setContextValue(offset, value)) {
          recordAppContextValue(offset, value);
          return true;
        }
      } catch (Throwable e) {
        log.debug("Failed to set context value", e);
      }
    }
    return false;
  }

  public boolean setContextValue(String attribute, String value) {
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

  /**
   * Clears the app-managed context attribute at {@code offset} on this thread (native slot plus the
   * per-thread snapshot). The underlying native {@code clearContextValue} is best-effort and
   * returns no status. No caller currently consumes the result; it is kept for symmetry with {@link
   * #setContextValue(int, String)} and to signal an unconfigured/failed clear.
   *
   * @param offset the app-managed context-attribute slot to clear; a negative value (an
   *     unconfigured attribute) is a no-op
   * @return {@code true} if a valid, non-negative {@code offset} was cleared without error; {@code
   *     false} if {@code offset} is negative or the native clear threw
   */
  public boolean clearContextValue(int offset) {
    if (offset >= 0) {
      try {
        // Native call first; snapshot updated only after it returns so a throw leaves both sides
        // consistent.
        profiler.clearContextValue(offset);
        recordAppContextValue(offset, null);
        return true;
      } catch (Throwable t) {
        log.debug("Failed to clear context value", t);
      }
    }
    return false;
  }

  /**
   * Re-applies this thread's application-managed context attributes after a span activation or
   * deactivation. The native {@code setTraceContext}/{@code clearTraceContext} clears all custom
   * attribute slots; this restores the app-owned ones so they remain visible during the new span's
   * lifetime — or after the last span closes, since native {@code setContextValue} publishes the
   * record (valid=1) even with no active span. No-op when no application attributes are configured
   * or none have been set on this thread.
   *
   * <p>Per-slot native {@code setContextValue} calls (each resolved through the process-wide value
   * cache, so no re-encoding on a hit). A single-JNI-call batch is a possible future optimization
   * (see java-profiler PROF-15361); per-slot preserves the prior shape and is adequate for the
   * typical small app-attribute count.
   */
  public void reapplyAppContext() {
    if (!hasAppContext) {
      return;
    }
    AppContextSnapshot snapshot = appContextValues.get();
    if (snapshot == null) {
      return;
    }
    try {
      int remaining = snapshot.nonZeroCount();
      for (int i = 0; i < isAppOffset.length && remaining > 0; i++) {
        String s = snapshot.stringAt(i);
        if (s != null) {
          profiler.setContextValue(i, s);
          remaining--;
        }
      }
    } catch (Throwable e) {
      log.debug("Failed to reapply context", e);
    }
  }

  /** Clears the per-thread app-context snapshot. Used in tests and internally. */
  void clearAppContextSnapshot() {
    appContextValues.remove();
    scopeStack.remove();
  }

  /**
   * Immediately writes {@code snapshot} into the ddprof native slots for every app-context offset,
   * clearing any offset not present in the snapshot. Reads the restored state from the per-thread
   * {@code appContextValues} TL (already updated by {@link #restoreAppContext}) rather than from
   * the saved slot directly, because {@link ScopeStack#release()} resets the slot before this
   * method is called. Called by {@link DatadogProfilingScope#close} so that native state matches
   * the restored Java-side snapshot right away, without waiting for the next span activation.
   */
  void syncNativeAppContext() {
    if (!hasAppContext) {
      return;
    }
    AppContextSnapshot snapshot = appContextValues.get();
    try {
      for (int i = 0; i < isAppOffset.length; i++) {
        if (!isAppOffset[i]) {
          continue;
        }
        String value = snapshot != null ? snapshot.stringAt(i) : null;
        if (value != null) {
          profiler.setContextValue(i, value);
        } else {
          profiler.clearContextValue(i);
        }
      }
    } catch (Throwable e) {
      log.debug("Failed to sync native app context on scope close", e);
    }
  }

  /**
   * Returns a copy of the current app-context snapshot for later restoration, or {@code null} if
   * nothing is set. Called by {@link DatadogProfilingScope} on construction to implement
   * save/restore across scope boundaries.
   */
  AppContextSnapshot saveAppContext() {
    AppContextSnapshot current = appContextValues.get();
    if (current == null || current.isEmpty()) {
      return null;
    }
    ScopeStack stack = scopeStack.get();
    if (stack == null) {
      stack = new ScopeStack(isAppOffset.length);
      scopeStack.set(stack);
    }
    AppContextSnapshot slot = stack.borrow();
    slot.copyFrom(current);
    return slot;
  }

  /**
   * Restores a previously saved app-context snapshot. If {@code saved} is {@code null} the
   * ThreadLocal is removed, otherwise the current snapshot is overwritten with {@code saved}.
   * Called by {@link DatadogProfilingScope#close()}.
   */
  void restoreAppContext(AppContextSnapshot saved) {
    if (saved == null) {
      appContextValues.remove();
    } else {
      AppContextSnapshot current = appContextValues.get();
      if (current == null) {
        current = new AppContextSnapshot(isAppOffset.length);
        appContextValues.set(current);
      }
      current.copyFrom(saved);
      ScopeStack stack = scopeStack.get();
      if (stack != null) {
        stack.release();
      }
    }
  }

  private void recordAppContextValue(int offset, String value) {
    if (!hasAppContext || offset < 0 || offset >= isAppOffset.length || !isAppOffset[offset]) {
      return;
    }
    AppContextSnapshot snapshot = appContextValues.get();
    if (value == null) {
      if (snapshot == null) {
        return;
      }
      snapshot.clear(offset);
      if (snapshot.isEmpty()) {
        appContextValues.remove();
      }
      return;
    }
    if (snapshot == null) {
      snapshot = new AppContextSnapshot(isAppOffset.length);
      appContextValues.set(snapshot);
    }
    // The all-native path resolves the value → encoding via the process-wide cache on reapply, so
    // the snapshot only needs the String (no per-thread snapshotTags read of the encoding).
    if (!value.equals(snapshot.stringAt(offset))) {
      snapshot.record(offset, value);
    }
  }

  private void debugLogging(long localRootSpanId) {
    if (detailedDebugLogging && log.isDebugEnabled()) {
      log.debug("localRootSpanId={}", localRootSpanId, new Throwable());
    }
  }

  public int[] snapshot() {
    int n = isAppOffset.length;
    if (n == 0) {
      return EMPTY;
    }
    // Native read of the current thread's sidecar tag encodings — no ThreadContext / DBB (which
    // would reset the record). Observes encodings written through the all-native path.
    int[] tags = new int[n];
    profiler.copyContextTags(tags);
    return tags;
  }

  public void recordSetting(String name, String value) {
    profiler.recordSetting(name, value);
  }

  public void recordSetting(String name, String value, String unit) {
    profiler.recordSetting(name, value, unit);
  }

  public QueueTimeTracker newQueueTimeTracker() {
    return new QueueTimeTracker(this, profiler.getCurrentTicks());
  }

  boolean shouldRecordQueueTimeEvent(long startMillis) {
    return System.currentTimeMillis() - startMillis >= queueTimeThresholdMillis;
  }

  void recordQueueTimeEvent(
      long startTicks,
      Object task,
      Class<?> scheduler,
      Class<?> queueType,
      int queueLength,
      Thread origin) {
    if (profiler != null) {
      // note: because this type traversal can update secondary_super_cache (see JDK-8180450)
      // we avoid doing this unless we are absolutely certain we will record the event
      Class<?> taskType = TaskWrapper.getUnwrappedType(task);
      if (taskType != null) {
        long endTicks = profiler.getCurrentTicks();
        profiler.recordQueueTime(
            startTicks, endTicks, taskType, scheduler, queueType, queueLength, origin);
      }
    }
  }
}
