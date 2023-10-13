package com.datadog.profiling.auxiliary.ddprof;

import com.datadog.profiling.auxiliary.AuxiliaryImplementation;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.ddprof.DatadogProfiler;
import com.datadog.profiling.utils.ProfilingMode;
import com.google.auto.service.AutoService;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.EnumSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
      return new AuxiliaryDatadogProfiler();
    }
  }

  private final DatadogProfiler datadogProfiler;

  AuxiliaryDatadogProfiler() {
    DatadogProfiler instance;
    try {
      instance = DatadogProfiler.getInstance();
    } catch (Throwable t) {
      log.debug("Datadog Profiler is not available", t);
      instance = null;
    }
    datadogProfiler = instance;
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
    if (datadogProfiler != null) {
      return datadogProfiler.stop(recording);
    }
    return null;
  }

  @Override
  public Set<ProfilingMode> enabledModes() {
    if (datadogProfiler != null) {
      return datadogProfiler.enabledModes();
    }
    return EnumSet.noneOf(ProfilingMode.class);
  }
}
