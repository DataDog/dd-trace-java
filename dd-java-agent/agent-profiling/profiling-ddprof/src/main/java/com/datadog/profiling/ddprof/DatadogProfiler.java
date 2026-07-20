// Copyright 2026 Datadog, Inc.
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
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getWallPrecheck;
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
import com.datadoghq.profiler.TaskBlockBridge;
import datadog.environment.JavaVirtualMachine;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.TaskWrapper;
import datadog.trace.util.TempLocationManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

  // JavaProfiler owns one process-wide native recording; all wrapper instances must observe the
  // same lifecycle so early-created integrations can dispatch hooks while the controller records.
  private static final AtomicBoolean RECORDING = new AtomicBoolean(false);
  private final ConfigProvider configProvider;
  private final JavaProfiler profiler;
  private final TaskBlockBridge taskBlockBridge;
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
   * setContextValue for an app attribute ever allocate. Holds the ddprof constant ID and
   * pre-encoded UTF-8 bytes for each slot, ready for a zero-allocation reapply via
   * setContextValuesByIdAndBytes.
   */
  private final ThreadLocal<AppContextSnapshot> appContextValues = new ThreadLocal<>();

  private final ThreadLocal<ScopeStack> scopeStack = new ThreadLocal<>();
  // Scratch buffer for snapshotTags in recordAppContextValue; per-thread, sized to context slots.
  // Lives here rather than on AppContextSnapshot so save-slots in ScopeStack don't carry it.
  private final ThreadLocal<int[]> contextScratch =
      new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
          return new int[isAppOffset.length];
        }
      };

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
    private final int[] ids;
    private final byte[][] utf8;
    // Cached string values for change detection — avoids re-encoding and re-snapshotting
    // the constant ID when the same value is set again on the same thread.
    private final String[] strings;
    // Count of slots with a non-zero constant ID; allows O(1) isEmpty().
    private int nonZeroCount;

    AppContextSnapshot(int size) {
      ids = new int[size];
      utf8 = new byte[size][];
      strings = new String[size];
    }

    void record(int offset, int constantId, byte[] utf8Bytes, String value) {
      if (ids[offset] == 0 && constantId != 0) {
        nonZeroCount++;
      } else if (ids[offset] != 0 && constantId == 0) {
        nonZeroCount--;
      }
      ids[offset] = constantId;
      utf8[offset] = utf8Bytes;
      strings[offset] = value;
    }

    void clear(int offset) {
      if (ids[offset] != 0) nonZeroCount--;
      ids[offset] = 0;
      utf8[offset] = null;
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

    int[] ids() {
      return ids;
    }

    byte[][] utf8() {
      return utf8;
    }

    void copyFrom(AppContextSnapshot src) {
      System.arraycopy(src.ids, 0, ids, 0, ids.length);
      System.arraycopy(src.utf8, 0, utf8, 0, utf8.length);
      System.arraycopy(src.strings, 0, strings, 0, strings.length);
      nonZeroCount = src.nonZeroCount;
    }

    void reset() {
      Arrays.fill(ids, 0);
      Arrays.fill(utf8, null);
      Arrays.fill(strings, null);
      nonZeroCount = 0;
    }
  }

  private final long queueTimeThresholdMillis;

  private final Path recordingsPath;

  private DatadogProfiler(ConfigProvider configProvider) {
    this.configProvider = configProvider;
    this.profiler = DdprofLibraryLoader.javaProfiler().getComponent();
    this.taskBlockBridge = new TaskBlockBridge(profiler);
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
    int contextSize = contextSetter.snapshotTags().length;
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
    if (RECORDING.compareAndSet(true, false)) {
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
    if (RECORDING.compareAndSet(false, true)) {
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
        RECORDING.set(false);
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
      boolean contextScope = getWallContextFilter(configProvider);
      // Emit both protocols during the compatibility window. Older ddprof versions ignore the
      // unknown wallscope option and use filter; newer versions use wallscope while continuing to
      // accept filter. This cannot be feature-probed because unknown options are warnings, not
      // execution errors.
      cmd.append(contextScope ? ",filter=0,wallscope=context" : ",filter=,wallscope=all");
      cmd.append(",wallprecheck=").append(getWallPrecheck(configProvider));
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

  public void setSpanContext(long rootSpanId, long spanId, long traceIdHigh, long traceIdLow) {
    debugLogging(rootSpanId);
    try {
      profiler.setContext(rootSpanId, spanId, traceIdHigh, traceIdLow);
    } catch (Throwable e) {
      log.debug("Failed to clear context", e);
    }
    reapplyAppContext();
  }

  public void clearSpanContext() {
    debugLogging(0L);
    try {
      profiler.setContext(0L, 0L, 0L, 0L);
    } catch (Throwable e) {
      log.debug("Failed to set context", e);
    }
    reapplyAppContext();
  }

  boolean hasParkTaskBlockSupport() {
    return taskBlockBridge.hasParkSupport();
  }

  boolean parkEnter() {
    if (!RECORDING.get()) {
      return false;
    }
    taskBlockBridge.parkEnter();
    return true;
  }

  void parkExit(long blocker, long unblockingSpanId) {
    taskBlockBridge.parkExit(blocker, unblockingSpanId);
  }

  long beginTaskBlock() {
    return RECORDING.get() ? taskBlockBridge.beginTaskBlock() : 0L;
  }

  boolean endTaskBlock(long token, long blocker, long unblockingSpanId) {
    return token != 0L && taskBlockBridge.endTaskBlock(token, blocker, unblockingSpanId);
  }

  public boolean setContextValue(int offset, String value) {
    if (contextSetter != null && offset >= 0 && value != null) {
      try {
        // Native call first; snapshot updated only on success so Java and ddprof state stay in
        // sync.
        if (contextSetter.setContextValue(offset, value)) {
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

  public boolean clearContextValue(int offset) {
    if (contextSetter != null && offset >= 0) {
      try {
        // Native call first; snapshot updated only after it returns so a throw leaves both sides
        // consistent.
        boolean cleared = contextSetter.clearContextValue(offset);
        recordAppContextValue(offset, null);
        return cleared;
      } catch (Throwable t) {
        log.debug("Failed to clear context value", t);
      }
    }
    return false;
  }

  /**
   * Re-applies this thread's application-managed context attributes after a span activation or
   * deactivation. ddprof's {@code setContext} clears all custom attribute slots; this restores only
   * the app-owned ones so they remain visible during the new span's lifetime (or after the last
   * span closes). No-op when no application attributes are configured or none have been set on this
   * thread.
   *
   * <p>Fast path (span activation): uses the pre-computed constant IDs and UTF-8 bytes from {@link
   * #recordAppContextValue} in a single {@code setContextValuesByIdAndBytes} call — no String
   * allocation, no hash lookup.
   *
   * <p>Fallback (span deactivation via {@code setContext(0,0,0,0)}): {@code clearContextDirect}
   * calls {@code detach()} but not {@code attach()}, leaving the thread's {@code validOffset=0}.
   * {@code setContextValuesByIdAndBytes} returns {@code false} in that state, so we fall back to
   * individual {@code setContextValue} calls which go through the proper detach/attach cycle.
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
      if (!contextSetter.setContextValuesByIdAndBytes(snapshot.ids(), snapshot.utf8())) {
        // validOffset=0 after clearContextDirect (setContext(0,0,0,0) path) — fall back to
        // individual writes which go through the proper detach/attach cycle.
        int remaining = snapshot.nonZeroCount();
        for (int i = 0; i < isAppOffset.length && remaining > 0; i++) {
          String s = snapshot.stringAt(i);
          if (s != null) {
            contextSetter.setContextValue(i, s);
            remaining--;
          }
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
    contextScratch.remove();
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
    if (!hasAppContext || contextSetter == null) {
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
          contextSetter.setContextValue(i, value);
        } else {
          contextSetter.clearContextValue(i);
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
    if (!value.equals(snapshot.stringAt(offset))) {
      byte[] utf8Bytes = value.getBytes(StandardCharsets.UTF_8);
      // ContextSetter has no single-slot readback API; snapshotTags fills all slots at once.
      // The scratch array is per-thread and reused across calls, so this is allocation-free.
      int[] scratch = contextScratch.get();
      contextSetter.snapshotTags(scratch);
      snapshot.record(offset, scratch[offset], utf8Bytes, value);
    }
  }

  private void debugLogging(long localRootSpanId) {
    if (detailedDebugLogging && log.isDebugEnabled()) {
      log.debug("localRootSpanId={}", localRootSpanId, new Throwable());
    }
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
