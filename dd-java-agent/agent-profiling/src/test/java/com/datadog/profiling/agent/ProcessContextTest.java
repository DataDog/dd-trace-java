package com.datadog.profiling.agent;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadoghq.profiler.OTelContext;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.api.Config;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ProcessContextTest {

  @Test
  void testRegisterSetsProcessContextValues() {
    ConfigProvider configProvider = mock(ConfigProvider.class);
    when(configProvider.getBoolean(
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED),
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED_DEFAULT)))
        .thenReturn(true);

    Config config = mock(Config.class);
    when(config.getEnv()).thenReturn("test-env");
    when(config.getHostName()).thenReturn("test-host");
    when(config.getRuntimeId()).thenReturn("test-runtime-id");
    when(config.getServiceName()).thenReturn("test-service");
    when(config.getRuntimeVersion()).thenReturn("test-runtime-version");
    when(config.getVersion()).thenReturn("test-version");

    OTelContext otelContext = mock(OTelContext.class);
    DdprofLibraryLoader.OTelContextHolder holder =
        mock(DdprofLibraryLoader.OTelContextHolder.class);
    when(holder.getReasonNotLoaded()).thenReturn(null);
    when(holder.getComponent()).thenReturn(otelContext);

    try (MockedStatic<Config> configMock = mockStatic(Config.class);
        MockedStatic<DdprofLibraryLoader> ddprofMock = mockStatic(DdprofLibraryLoader.class)) {

      configMock.when(Config::get).thenReturn(config);
      ddprofMock.when(DdprofLibraryLoader::otelContext).thenReturn(holder);

      ProcessContext.register(configProvider);

      verify(otelContext)
          .setProcessContext(
              eq("test-env"),
              eq("test-host"),
              eq("test-runtime-id"),
              eq("test-service"),
              eq("test-runtime-version"),
              eq("test-version"));
    }
  }

  @Test
  void testRegisterSkipsWhenDisabled() {
    ConfigProvider configProvider = mock(ConfigProvider.class);
    when(configProvider.getBoolean(
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED),
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED_DEFAULT)))
        .thenReturn(false);

    DdprofLibraryLoader.OTelContextHolder holder =
        mock(DdprofLibraryLoader.OTelContextHolder.class);

    try (MockedStatic<DdprofLibraryLoader> ddprofMock = mockStatic(DdprofLibraryLoader.class)) {
      ddprofMock.when(DdprofLibraryLoader::otelContext).thenReturn(holder);

      ProcessContext.register(configProvider);

      verify(holder, org.mockito.Mockito.never()).getReasonNotLoaded();
      verify(holder, org.mockito.Mockito.never()).getComponent();
    }
  }

  @Test
  void testRegisterSkipsByDefault() {
    ConfigProvider configProvider = mock(ConfigProvider.class);
    when(configProvider.getBoolean(
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED),
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED_DEFAULT)))
        .thenReturn(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED_DEFAULT);

    DdprofLibraryLoader.OTelContextHolder holder =
        mock(DdprofLibraryLoader.OTelContextHolder.class);

    try (MockedStatic<DdprofLibraryLoader> ddprofMock = mockStatic(DdprofLibraryLoader.class)) {
      ddprofMock.when(DdprofLibraryLoader::otelContext).thenReturn(holder);

      ProcessContext.register(configProvider);

      verify(holder, org.mockito.Mockito.never()).getReasonNotLoaded();
      verify(holder, org.mockito.Mockito.never()).getComponent();
    }
  }

  @Test
  void testRegisterHandlesLibraryLoadFailure() {
    ConfigProvider configProvider = mock(ConfigProvider.class);
    when(configProvider.getBoolean(
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED),
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED_DEFAULT)))
        .thenReturn(true);

    Throwable loadError = new RuntimeException("Library load failed");
    DdprofLibraryLoader.OTelContextHolder holder =
        mock(DdprofLibraryLoader.OTelContextHolder.class);
    when(holder.getReasonNotLoaded()).thenReturn(loadError);

    try (MockedStatic<DdprofLibraryLoader> ddprofMock = mockStatic(DdprofLibraryLoader.class)) {
      ddprofMock.when(DdprofLibraryLoader::otelContext).thenReturn(holder);

      ProcessContext.register(configProvider);

      verify(holder).getReasonNotLoaded();
      verify(holder, org.mockito.Mockito.never()).getComponent();
    }
  }
}
