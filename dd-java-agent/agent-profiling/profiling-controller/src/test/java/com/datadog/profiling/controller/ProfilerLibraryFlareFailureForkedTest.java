package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import datadog.environment.OperatingSystem;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.TempLocationManager;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/** The flare makes the first, failing native library load attempt in this JVM. */
class ProfilerLibraryFlareFailureForkedTest {
  @TempDir Path temp;

  @Test
  void reportsCauseOnceAndThenTheEarlierFailure() {
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

      StringBuilder first = new StringBuilder();
      assertFalse(EnvironmentChecker.checkEnvironment(first));
      assertTrue(first.toString().contains("UnsatisfiedLinkError"));
      assertTrue(first.toString().contains(missingLibrary));

      // the loader does not retry and reports the original error only once
      StringBuilder second = new StringBuilder();
      assertFalse(EnvironmentChecker.checkEnvironment(second));
      assertTrue(second.toString().contains("An earlier native library load failed"));
      assertTrue(second.toString().contains("Check application startup logs"));
    }
  }
}
