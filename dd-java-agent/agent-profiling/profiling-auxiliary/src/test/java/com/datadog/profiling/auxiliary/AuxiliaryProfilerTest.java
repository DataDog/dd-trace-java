package com.datadog.profiling.auxiliary;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.EnumSet;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.internal.verification.VerificationModeFactory;
import org.mockito.stubbing.Answer;

class AuxiliaryProfilerTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "none", "custom"})
  void testNoAuxiliary(String auxiliaryType) {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_AUXILIARY_TYPE, auxiliaryType);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    AuxiliaryProfiler profiler = new AuxiliaryProfiler(configProvider);
    assertFalse(profiler.isEnabled());
    assertTrue(profiler.enabledModes().isEmpty());

    OngoingRecording rec = profiler.start();
    assertNull(rec);
    assertDoesNotThrow(() -> profiler.stop(rec));
  }

  @Test
  void testAuxiliaryFromServiceLoader() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_AUXILIARY_TYPE, TestAuxiliaryProfilerImplementation.TYPE);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    AuxiliaryProfiler profiler = new AuxiliaryProfiler(configProvider);
    assertTrue(profiler.isEnabled());
    assertFalse(profiler.enabledModes().isEmpty());

    OngoingRecording rec = profiler.start();
    assertNotNull(rec);
    assertDoesNotThrow(() -> profiler.stop(rec));
  }

  @Test
  void testAuxiliary() {
    AuxiliaryImplementation impl = Mockito.mock(AuxiliaryImplementation.class);
    Mockito.when(impl.isAvailable()).thenReturn(true);
    Mockito.when(impl.enabledModes()).thenReturn(EnumSet.allOf(ProfilingMode.class));
    Mockito.when(impl.start())
        .then((Answer<OngoingRecording>) invocation -> Mockito.mock(OngoingRecording.class));

    AuxiliaryProfiler profiler = new AuxiliaryProfiler(impl);

    assertTrue(profiler.isEnabled());
    assertFalse(profiler.enabledModes().isEmpty());

    OngoingRecording rec = profiler.start();
    assertNotNull(rec);
    assertDoesNotThrow(() -> profiler.stop(rec));

    Mockito.verify(impl, VerificationModeFactory.times(1)).start();
    Mockito.verify(impl, VerificationModeFactory.times(1))
        .stop(ArgumentMatchers.any(OngoingRecording.class));
  }

  @Test
  void testSingletonDefault() {
    // this will default to 'none' auxiliary profiler
    AuxiliaryProfiler profiler = AuxiliaryProfiler.getInstance();

    assertFalse(profiler.isEnabled());
    assertTrue(profiler.enabledModes().isEmpty());

    OngoingRecording rec = profiler.start();
    assertNull(rec);
    assertDoesNotThrow(() -> profiler.stop(rec));
  }
}
