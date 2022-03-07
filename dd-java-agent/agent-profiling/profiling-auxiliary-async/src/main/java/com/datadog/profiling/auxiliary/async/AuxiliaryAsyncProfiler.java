package com.datadog.profiling.auxiliary.async;

import com.datadog.profiling.auxiliary.AuxiliaryImplementation;
import com.datadog.profiling.auxiliary.LibraryHelper;
import com.datadog.profiling.auxiliary.ProfilingMode;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import com.google.auto.service.AutoService;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import one.profiler.AsyncProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AuxiliaryAsyncProfiler implements AuxiliaryImplementation {
  private static final Logger log = LoggerFactory.getLogger(AuxiliaryAsyncProfiler.class);

  public static final String TYPE = "async";

  private final long memleakIntervalDefault;

  @AutoService(AuxiliaryImplementation.Provider.class)
  public static final class ImplementerProvider implements AuxiliaryImplementation.Provider {
    @Override
    public boolean canProvide(String expectedType) {
      return TYPE.equals(expectedType);
    }

    @Override
    @Nonnull
    public AuxiliaryImplementation provide(ConfigProvider configProvider) {
      return new AuxiliaryAsyncProfiler(configProvider);
    }
  }

  private final AtomicBoolean recordingFlag = new AtomicBoolean(false);
  private final ConfigProvider configProvider;
  private final AsyncProfiler asyncProfiler;
  private final Set<ProfilingMode> profilingModes = EnumSet.noneOf(ProfilingMode.class);

  AuxiliaryAsyncProfiler(ConfigProvider configProvider) {
    this.configProvider = configProvider;
    AsyncProfiler instance = null;
    String libDir = configProvider.getString(ProfilingConfig.PROFILING_ASYNC_LIBPATH);
    if (libDir != null && Files.exists(Paths.get(libDir))) {
      // the library from configuration takes precedence
      instance = AsyncProfiler.getInstance(libDir);
    } else {
      instance = inferFromOsAndArch();
    }
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_ASYNC_ALLOC_ENABLED,
        ProfilingConfig.PROFILING_ASYNC_ALLOC_ENABLED_DEFAULT)) {
      profilingModes.add(ProfilingMode.ALLOCATION);
    }
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_ASYNC_MEMLEAK_ENABLED,
        ProfilingConfig.PROFILING_ASYNC_MEMLEAK_ENABLED_DEFAULT)) {
      profilingModes.add(ProfilingMode.MEMLEAK);
    }
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_ASYNC_CPU_ENABLED,
        ProfilingConfig.PROFILING_ASYNC_CPU_ENABLED_DEFAULT)) {
      profilingModes.add(ProfilingMode.CPU);
    }
    try {
      // sanity test - force load async profiler to catch it not being available early
      instance.execute("status");
    } catch (Throwable t) {
      log.debug("Async Profiler is not available", t);
      instance = null;
    }
    if (instance != null) {
      try {
        // make sure JFR is accessible
        EventType.getEventType(AsyncProfilerConfigEvent.class);
      } catch (NoClassDefFoundError ignored) {
        // if JFR is not accessible disable the async profiler integration
        instance = null;
      }
    }
    asyncProfiler = instance;
    if (instance != null) {
      FlightRecorder.addPeriodicEvent(AsyncProfilerConfigEvent.class, this::emitConfiguration);
    }

    long maxheap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    this.memleakIntervalDefault =
        maxheap <= 0 ? 1 * 1024 * 1024 : maxheap / Math.max(1, getMemleakCapacity());
  }

  private void emitConfiguration() {
    try {
      new AsyncProfilerConfigEvent(
              asyncProfiler.getVersion(),
              configProvider.getString(ProfilingConfig.PROFILING_ASYNC_LIBPATH),
              getCpuInterval(),
              getAllocationInterval(),
              getMemleakInterval(),
              getMemleakCapacity(),
              ProfilingMode.mask(profilingModes))
          .commit();
    } catch (Throwable t) {
      if (log.isDebugEnabled()) {
        log.warn("Exception occurred while attempting to emit config event", t);
      } else {
        log.warn("Exception occurred while attempting to emit config event", t.toString());
      }
      throw t;
    }
  }

  private static AsyncProfiler inferFromOsAndArch() {
    Arch arch = Arch.current();
    OperatingSystem os = OperatingSystem.current();
    try {
      if (os != OperatingSystem.unknown) {
        if (arch != Arch.unknown) {
          try {
            return profilerForOsAndArch(os, arch, false);
          } catch (FileNotFoundException e) {
            if (os == OperatingSystem.linux) {
              // Might be a MUSL distribution
              return profilerForOsAndArch(os, arch, true);
            }
            throw e;
          }
        }
      }
    } catch (Throwable t) {
      if (log.isDebugEnabled()) {
        log.info(
            "Unable to instantiate async profiler for the detected environment: arch={}, os={}",
            arch,
            os,
            t);
      } else {
        log.info(
            "Unable to instantiate async profiler for the detected environment: arch={}, os={}, cause={}",
            arch,
            os,
            t.getMessage());
      }
    }
    return null;
  }

  private static AsyncProfiler profilerForOsAndArch(OperatingSystem os, Arch arch, boolean musl)
      throws IOException {
    String libDir =
        os.name() + (os.name().equals("macos") ? "" : (musl ? "-musl-" : "-") + arch.name());
    File localLib =
        LibraryHelper.libraryFromClasspath("/native-libs/" + libDir + "/libasyncProfiler.so");
    return AsyncProfiler.getInstance(localLib.getAbsolutePath());
  }

  @Override
  public boolean isAvailable() {
    return asyncProfiler != null;
  }

  @Override
  @Nullable
  public OngoingRecording start() {
    if (asyncProfiler != null) {
      log.debug("Starting profiling");
      try {
        return new AsyncProfilerRecording(this);
      } catch (IOException | IllegalStateException e) {
        return null;
      }
    }
    return null;
  }

  @Override
  @Nullable
  public RecordingData stop(OngoingRecording recording) {
    try {
      if (asyncProfiler != null) {
        log.debug("Stopping profiling");
        return recording.stop();
      }
      return null;
    } finally {
      FlightRecorder.removePeriodicEvent(this::emitConfiguration);
    }
  }

  /** A call-back from {@linkplain AsyncProfilerRecording#stop()} */
  void stopProfiler() {
    if (asyncProfiler != null) {
      if (recordingFlag.compareAndSet(true, false)) {
        asyncProfiler.stop();
        if (isActive()) {
          log.debug("Profiling is still active. Waiting to stop.");
          while (isActive()) {
            LockSupport.parkNanos(10_000_000L);
          }
        }
      }
    }
  }

  @Override
  public Set<ProfilingMode> enabledModes() {
    return profilingModes;
  }

  boolean isActive() {
    if (!isAvailable()) {
      return false;
    }
    try {
      String status = executeProfilerCmd("status");
      log.debug("Async Profiler Status = {}", status);
      return !status.contains("not active");
    } catch (IOException ignored) {
    }
    return false;
  }

  String executeProfilerCmd(String cmd) throws IOException {
    return asyncProfiler.execute(cmd);
  }

  Path newRecording() throws IOException, IllegalStateException {
    if (recordingFlag.compareAndSet(false, true)) {
      Path recFile = Files.createTempFile("dd-profiler-", ".jfr");
      String cmd = cmdStartProfiling(recFile);
      try {
        String rslt = executeProfilerCmd(cmd);
        log.debug("AsyncProfiler.execute({}) = {}", cmd, rslt);
      } catch (IOException | IllegalStateException e) {
        if (log.isDebugEnabled()) {
          log.warn("Unable to start async profiler recording", e);
        } else {
          log.warn("Unable to start async profiler recording: {}", e.getMessage());
        }
        recordingFlag.set(false);
        throw e;
      }
      return recFile;
    }
    throw new IllegalStateException("Async profiler session has already been started");
  }

  String cmdStartProfiling(Path file) throws IllegalStateException {
    // 'start' = start, 'jfr=7' = store in JFR format ready for concatenation
    StringBuilder cmd = new StringBuilder("start,jfr=7");
    cmd.append(",file=").append(file.toAbsolutePath());
    cmd.append(",loglevel=").append(getLogLevel());
    if (profilingModes.contains(ProfilingMode.CPU)) {
      // cpu profiling is enabled.
      cmd.append(",event=")
          .append(getCpuMode())
          .append(",interval=")
          .append(getCpuInterval())
          .append('m')
          .append(",jstackdepth=")
          .append(getStackDepth())
          .append(",safemode=")
          .append(getSafeMode());
    }
    if (profilingModes.contains(ProfilingMode.ALLOCATION)) {
      // allocation profiling is enabled
      cmd.append(",alloc=").append(getAllocationInterval()).append('b');
    }
    if (profilingModes.contains(ProfilingMode.MEMLEAK)) {
      // memleak profiling is enabled
      cmd.append(",memleak=")
          .append(getMemleakInterval())
          .append('b')
          .append(",memleakcap=")
          .append(getMemleakCapacity())
          .append('b');
    }
    String cmdString = cmd.toString();
    log.debug("Async profiler command line: {}", cmdString);
    return cmdString;
  }

  private int getAllocationInterval() {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_ASYNC_ALLOC_INTERVAL,
        ProfilingConfig.PROFILING_ASYNC_ALLOC_INTERVAL_DEFAULT);
  }

  private int getCpuInterval() {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_ASYNC_CPU_INTERVAL,
        ProfilingConfig.PROFILING_ASYNC_CPU_INTERVAL_DEFAULT);
  }

  private int getStackDepth() {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_ASYNC_CPU_STACKDEPTH,
        ProfilingConfig.PROFILING_ASYNC_CPU_STACKDEPTH_DEFAULT);
  }

  private int getSafeMode() {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_ASYNC_CPU_SAFEMODE,
        ProfilingConfig.PROFILING_ASYNC_CPU_SAFEMODE_DEFAULT);
  }

  private String getCpuMode() {
    return configProvider.getString(
        ProfilingConfig.PROFILING_ASYNC_CPU_MODE, ProfilingConfig.PROFILING_ASYNC_CPU_MODE_DEFAULT);
  }

  private long getMemleakInterval() {
    return configProvider.getLong(
        ProfilingConfig.PROFILING_ASYNC_MEMLEAK_INTERVAL, memleakIntervalDefault);
  }

  private int getMemleakCapacity() {
    return clamp(
        0,
        // see https://github.com/DataDog/async-profiler/blob/main/src/memleakTracer.h
        8192,
        configProvider.getInteger(
            ProfilingConfig.PROFILING_ASYNC_MEMLEAK_CAPACITY,
            ProfilingConfig.PROFILING_ASYNC_MEMLEAK_CAPACITY_DEFAULT));
  }

  private String getLogLevel() {
    if (log.isTraceEnabled()) {
      return "trace";
    }
    if (log.isDebugEnabled()) {
      return "debug";
    }
    if (log.isInfoEnabled()) {
      return "info";
    }
    if (log.isWarnEnabled()) {
      return "warn";
    }
    if (log.isErrorEnabled()) {
      return "error";
    }
    return "none";
  }

  private int clamp(int min, int max, int value) {
    return Math.max(min, Math.min(max, value));
  }
}
