package com.datadog.profiling.auxiliary;

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.utils.ProfilingMode;
import com.google.auto.service.AutoService;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.EnumSet;
import java.util.Set;
import org.mockito.Mockito;

public class TestAuxiliaryProfilerImplementation implements AuxiliaryImplementation {
  public static final String TYPE = "test";

  @AutoService(AuxiliaryImplementation.Provider.class)
  public static final class Provider implements AuxiliaryImplementation.Provider {
    @Override
    public boolean canProvide(String expectedType) {
      return expectedType.equals(TYPE);
    }

    @Override
    public AuxiliaryImplementation provide(ConfigProvider configProvider) {
      return new TestAuxiliaryProfilerImplementation();
    }
  }

  boolean isStarted = false;
  boolean isStopped = false;

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public Set<ProfilingMode> enabledModes() {
    return EnumSet.allOf(ProfilingMode.class);
  }

  @Override
  public OngoingRecording start() {
    isStarted = true;
    return Mockito.mock(OngoingRecording.class);
  }

  @Override
  public RecordingData stop(OngoingRecording recording) {
    isStopped = true;
    return Mockito.mock(RecordingData.class);
  }
}
