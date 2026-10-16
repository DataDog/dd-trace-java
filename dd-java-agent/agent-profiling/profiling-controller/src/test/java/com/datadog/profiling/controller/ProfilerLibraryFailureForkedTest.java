package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import datadog.environment.OperatingSystem;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.TempLocationManager;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.tabletest.junit.TableTest;

class ProfilerLibraryFailureForkedTest {
  @TempDir Path temp;

  @TableTest({
    "Scenario                   | Initialize profiler",
    "first attempt from flare   | false              ",
    "first attempt from startup | true               "
  })
  void reportsFirstAndPreviouslyFailedLoads(boolean initializeProfiler) throws Exception {
    String missingLibrary = temp.resolve("missing-library.so").toString();
    Properties properties = new Properties();
    properties.setProperty(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH, missingLibrary);
    properties.setProperty(ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH, temp.toString());
    ConfigProvider configuration = ConfigProvider.withPropertiesOverride(properties);
    TempLocationManager tempManager = mock(TempLocationManager.class);
    when(tempManager.getTempDir()).thenReturn(temp);

    try (MockedStatic<ConfigProvider> configurations = mockStatic(ConfigProvider.class);
        MockedStatic<TempLocationManager> locations = mockStatic(TempLocationManager.class);
        MockedStatic<OperatingSystem> os = mockStatic(OperatingSystem.class, CALLS_REAL_METHODS)) {
      configurations.when(ConfigProvider::getInstance).thenReturn(configuration);
      locations.when(TempLocationManager::getInstance).thenReturn(tempManager);
      os.when(OperatingSystem::isLinux).thenReturn(true);

      if (initializeProfiler) {
        DdprofLibraryLoader.JavaProfilerHolder holder = DdprofLibraryLoader.javaProfiler();
        assertNotNull(holder.getReasonNotLoaded());
      }

      for (int i = 0; i < 2; i++) {
        StringBuilder report = new StringBuilder();
        assertFalse(EnvironmentChecker.checkEnvironment(report));
        // a failed profiler initialization keeps its cause, the loader reports it only once
        if (initializeProfiler || i == 0) {
          assertTrue(report.toString().contains("UnsatisfiedLinkError"));
          assertTrue(report.toString().contains(missingLibrary));
        } else {
          assertTrue(report.toString().contains("An earlier native library load failed"));
          assertTrue(report.toString().contains("Check application startup logs"));
        }
      }
    }
  }
}
