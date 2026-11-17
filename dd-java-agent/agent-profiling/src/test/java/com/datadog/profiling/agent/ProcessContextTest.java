package com.datadog.profiling.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Tests {@link ProcessContext#register(ConfigProvider)}, the entry point that publishes the process
 * context attributes to the ddprof native {@link OTelContext}.
 *
 * <p>This method has two independent callers: {@code ProfilingAgent.run()} (the historical caller,
 * only reachable when profiling is enabled) and {@code Agent.createProfilingContextIntegration()},
 * which now invokes it reflectively whenever OTel context exposure is enabled - including the
 * AppSec-only, profiling-disabled case where {@code ProfilingAgent.run()} never executes. The
 * signature {@code register(ConfigProvider)} is pinned by a GraalVM {@code @Substitute} and by that
 * reflective lookup, so it must not change.
 */
class ProcessContextTest {

  @Test
  void testRegisterSetsProcessContextValues() {
    ConfigProvider configProvider = mock(ConfigProvider.class);
    when(configProvider.getBoolean(
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED),
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED_DEFAULT)))
        .thenReturn(true);
    when(configProvider.getSet(eq(ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES), any()))
        .thenReturn(new LinkedHashSet<>(Arrays.asList("http.route", "db.system")));

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
          .initializeAllContext(
              eq("test-env"),
              eq("test-host"),
              eq("test-runtime-id"),
              eq("test-service"),
              eq("test-runtime-version"),
              eq("test-version"),
              aryEq(new String[] {"http.route", "db.system"}));
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
  void testEnabledByDefault() {
    assertTrue(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED_DEFAULT);
  }

  /**
   * Covers the call path introduced for AppSec-only deployments: {@code
   * Agent.createProfilingContextIntegration()} reflectively calls {@code register(ConfigProvider)}
   * when OTel context exposure is enabled, even though {@code profiling.enabled} is {@code false}
   * and {@code ProfilingAgent.run()} is therefore never executed.
   *
   * <p>The process context gate is {@code profiling.process.context.enabled} alone: {@code
   * register} never reads {@code Config#isProfilingEnabled()}, so it is already profiling-agnostic.
   * This test pins that property by stubbing {@code isProfilingEnabled()} to {@code false} and
   * asserting the native context is still fully initialized, so a future change that made process
   * context depend on the profiler being enabled would break the new caller here rather than
   * silently in production.
   */
  @Test
  void testRegisterWorksIndependentlyOfProfilingEnabledState() {
    ConfigProvider configProvider = mock(ConfigProvider.class);
    when(configProvider.getBoolean(
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED),
            eq(ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED_DEFAULT)))
        .thenReturn(true);
    when(configProvider.getSet(eq(ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES), any()))
        .thenReturn(new LinkedHashSet<>(Collections.singletonList("http.route")));

    Config config = mock(Config.class);
    when(config.isProfilingEnabled()).thenReturn(false);
    when(config.getEnv()).thenReturn("appsec-env");
    when(config.getHostName()).thenReturn("appsec-host");
    when(config.getRuntimeId()).thenReturn("appsec-runtime-id");
    when(config.getServiceName()).thenReturn("appsec-service");
    when(config.getRuntimeVersion()).thenReturn("appsec-runtime-version");
    when(config.getVersion()).thenReturn("appsec-version");

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
          .initializeAllContext(
              eq("appsec-env"),
              eq("appsec-host"),
              eq("appsec-runtime-id"),
              eq("appsec-service"),
              eq("appsec-runtime-version"),
              eq("appsec-version"),
              aryEq(new String[] {"http.route"}));
    }
  }

  /**
   * The reflective call site in {@code Agent.createProfilingContextIntegration()} looks up {@code
   * register} by the exact signature {@code register(ConfigProvider)} and is unable to fail at
   * compile time if that signature changes. The same signature is pinned by a GraalVM
   * {@code @Substitute}. This test fails fast if the method is renamed or its parameter type
   * changes.
   */
  @Test
  void testRegisterSignatureIsStableForReflectiveLookup() throws NoSuchMethodException {
    Method register = ProcessContext.class.getMethod("register", ConfigProvider.class);

    assertTrue(Modifier.isStatic(register.getModifiers()));
    assertTrue(Modifier.isPublic(register.getModifiers()));
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
