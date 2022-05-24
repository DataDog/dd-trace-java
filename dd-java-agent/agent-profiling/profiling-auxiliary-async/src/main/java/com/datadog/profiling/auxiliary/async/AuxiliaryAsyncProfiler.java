package com.datadog.profiling.auxiliary.async;

import com.datadog.profiling.async.AsyncProfiler;
import com.datadog.profiling.auxiliary.AuxiliaryImplementation;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.utils.ProfilingMode;
import com.google.auto.service.AutoService;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jdk.jfr.FlightRecorder;
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
    AsyncProfiler instance;
    try {
      instance = AsyncProfiler.getInstance();
    } catch (Throwable t) {
      log.debug("Async Profiler is not available", t);
      instance = null;
    }
    asyncProfiler = instance;
    if (asyncProfiler != null) {
      FlightRecorder.addPeriodicEvent(AsyncProfilerConfigEvent.class, this::emitConfiguration);
    }
  }

  private void emitConfiguration() {
    try {
      new AsyncProfilerConfigEvent(
              asyncProfiler.getVersion(),
              configProvider.getString(ProfilingConfig.PROFILING_ASYNC_LIBPATH),
              asyncProfiler.getCpuInterval(),
              asyncProfiler.getAllocationInterval(),
              asyncProfiler.getMemleakInterval(),
              asyncProfiler.getMemleakCapacity(),
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

  @Override
  public boolean isAvailable() {
    return asyncProfiler != null && asyncProfiler.isAvailable();
  }

  @Override
  @Nullable
  public OngoingRecording start() {
    if (asyncProfiler != null) {
      return asyncProfiler.start();
    }
    return null;
  }

  @Override
  @Nullable
  public RecordingData stop(OngoingRecording recording) {
    try {
      if (asyncProfiler != null) {
        return asyncProfiler.stop(recording);
      }
      return null;
    } finally {
      FlightRecorder.removePeriodicEvent(this::emitConfiguration);
    }
  }

  @Override
  public Set<ProfilingMode> enabledModes() {
    if (asyncProfiler != null) {
      return asyncProfiler.enabledModes();
    }
    return EnumSet.noneOf(ProfilingMode.class);
  }
}
