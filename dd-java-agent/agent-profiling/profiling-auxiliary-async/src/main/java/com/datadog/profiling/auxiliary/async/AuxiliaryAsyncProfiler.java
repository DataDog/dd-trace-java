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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import one.profiler.AsyncProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AuxiliaryAsyncProfiler implements AuxiliaryImplementation {
  private static final Logger log = LoggerFactory.getLogger(AuxiliaryAsyncProfiler.class);

  public static final String TYPE = "async";

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
    if (configProvider.getBoolean(ProfilingConfig.PROFILING_ASYNC_ALLOC_ENABLED, false)) {
      profilingModes.add(ProfilingMode.ALLOCATION);
    }
    if (configProvider.getBoolean(ProfilingConfig.PROFILING_ASYNC_CPU_ENABLED, true)) {
      profilingModes.add(ProfilingMode.CPU);
    }
    try {
      // sanity test - force load async profiler to catch it not being available early
      instance.execute("status");
    } catch (Throwable t) {
      log.debug("Async Profiler is not available", t);
      instance = null;
    }
    asyncProfiler = instance;
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
    String libDir = os.name() + (musl ? "-musl-" : "-") + arch.name();
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
    if (asyncProfiler != null) {
      log.debug("Stopping profiling");
      return recording.stop();
    }
    return null;
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
    StringBuilder cmd = new StringBuilder("start,jfr=7,file=").append(file.toAbsolutePath());
    if (profilingModes.contains(ProfilingMode.CPU)) {
      // enable 'itimer' event to collect CPU samples
      cmd.append(",event=itimer");
      if (profilingModes.contains(ProfilingMode.ALLOCATION)) {
        // if combined with allocation profiling just set the allocation interval
        cmd.append(",alloc=")
            .append(
                configProvider.getString(
                    ProfilingConfig.PROFILING_ASYNC_ALLOC_INTERVAL,
                    ProfilingConfig.PROFILING_ASYNC_ALLOC_INTERVAL_DEFAULT));
      }
    } else if (profilingModes.contains(ProfilingMode.ALLOCATION)) {
      // only allocation profiling is enabled
      cmd.append(",event=alloc,alloc=")
          .append(
              configProvider.getString(
                  ProfilingConfig.PROFILING_ASYNC_ALLOC_INTERVAL,
                  ProfilingConfig.PROFILING_ASYNC_ALLOC_INTERVAL_DEFAULT));
    }
    return cmd.toString();
  }
}
