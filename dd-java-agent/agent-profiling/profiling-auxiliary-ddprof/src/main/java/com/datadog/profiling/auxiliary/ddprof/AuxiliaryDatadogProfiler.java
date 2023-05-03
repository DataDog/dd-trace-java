package com.datadog.profiling.auxiliary.ddprof;

import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getAllocationInterval;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getCpuInterval;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getLibPath;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getMemleakCapacity;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getMemleakInterval;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.getWallInterval;

import com.datadog.profiling.auxiliary.AuxiliaryImplementation;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.ddprof.DatadogProfiler;
import com.datadog.profiling.utils.ProfilingMode;
import com.google.auto.service.AutoService;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jdk.jfr.FlightRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AuxiliaryDatadogProfiler implements AuxiliaryImplementation {
  private static final Logger log = LoggerFactory.getLogger(AuxiliaryDatadogProfiler.class);

  public static final String TYPE = "ddprof";

  @AutoService(AuxiliaryImplementation.Provider.class)
  public static final class ImplementerProvider implements AuxiliaryImplementation.Provider {
    @Override
    public boolean canProvide(String expectedType) {
      return TYPE.equals(expectedType);
    }

    @Override
    @Nonnull
    public AuxiliaryImplementation provide(ConfigProvider configProvider) {
      return new AuxiliaryDatadogProfiler(configProvider);
    }
  }

  private final AtomicBoolean recordingFlag = new AtomicBoolean(false);
  private final ConfigProvider configProvider;
  private final DatadogProfiler datadogProfiler;

  AuxiliaryDatadogProfiler(ConfigProvider configProvider) {
    this.configProvider = configProvider;
    DatadogProfiler instance;
    try {
      instance = DatadogProfiler.getInstance();
    } catch (Throwable t) {
      log.debug("Datadog Profiler is not available", t);
      instance = null;
    }
    datadogProfiler = instance;
    if (datadogProfiler != null && datadogProfiler.isAvailable()) {
      FlightRecorder.addPeriodicEvent(DatadogProfilerConfigEvent.class, this::emitConfiguration);
    }
  }

  private void emitConfiguration() {
    assert datadogProfiler.isAvailable();
    try {
      new DatadogProfilerConfigEvent(
              datadogProfiler.getVersion(),
              getLibPath(configProvider),
              getCpuInterval(configProvider),
              getWallInterval(configProvider),
              getAllocationInterval(configProvider),
              getMemleakInterval(configProvider),
              getMemleakCapacity(configProvider),
              ProfilingMode.mask(datadogProfiler.enabledModes()))
          .commit();
    } catch (Throwable t) {
      if (log.isDebugEnabled()) {
        log.warn("Exception occurred while attempting to emit config event", t);
      }
    }
  }

  @Override
  public boolean isAvailable() {
    return datadogProfiler != null && datadogProfiler.isAvailable();
  }

  @Override
  @Nullable
  public OngoingRecording start() {
    if (datadogProfiler != null && datadogProfiler.isAvailable()) {
      return datadogProfiler.start();
    }
    return null;
  }

  @Override
  @Nullable
  public RecordingData stop(OngoingRecording recording) {
    try {
      if (datadogProfiler != null) {
        return datadogProfiler.stop(recording);
      }
      return null;
    } finally {
      FlightRecorder.removePeriodicEvent(this::emitConfiguration);
    }
  }

  @Override
  public Set<ProfilingMode> enabledModes() {
    if (datadogProfiler != null) {
      return datadogProfiler.enabledModes();
    }
    return EnumSet.noneOf(ProfilingMode.class);
  }
}
