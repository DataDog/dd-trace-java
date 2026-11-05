package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadoghq.profiler.JavaProfiler;
import com.datadoghq.profiler.LibraryLoader;
import datadog.environment.OperatingSystem;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.TempLocationManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.tabletest.junit.TableTest;

class ProfilerLibraryLoadingTest {
  @TempDir Path temp;

  @TableTest({
    "Scenario                     | Custom scratch | Custom library",
    "default locations            | false          | false         ",
    "scratch override             | true           | false         ",
    "library override             | false          | true          ",
    "scratch and library override | true           | true          "
  })
  void loadOnlyCheckUsesProfilerConfiguration(boolean customScratch, boolean customLibrary)
      throws Exception {
    Properties properties = new Properties();
    Path scratch = temp.resolve(customScratch ? "custom-scratch" : "scratch");
    String libraryPath = customLibrary ? temp.resolve("custom-library.so").toString() : null;
    if (customScratch) {
      Files.createDirectory(scratch);
      properties.setProperty(
          ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH, scratch.toString());
    }
    if (customLibrary) {
      properties.setProperty(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH, libraryPath);
    }
    ConfigProvider configuration = ConfigProvider.withPropertiesOverride(properties);
    TempLocationManager tempManager = mock(TempLocationManager.class);
    when(tempManager.getTempDir()).thenReturn(temp);
    LibraryLoader.Builder builder = mock(LibraryLoader.Builder.class, RETURNS_SELF);
    when(builder.load()).thenReturn(LibraryLoader.Result.SUCCESS);

    try (MockedStatic<ConfigProvider> configurations = mockStatic(ConfigProvider.class);
        MockedStatic<TempLocationManager> tempLocations = mockStatic(TempLocationManager.class);
        MockedStatic<LibraryLoader> libraries = mockStatic(LibraryLoader.class);
        MockedStatic<JavaProfiler> profiler = mockStatic(JavaProfiler.class);
        MockedStatic<OperatingSystem> os = mockStatic(OperatingSystem.class, CALLS_REAL_METHODS)) {
      configurations.when(ConfigProvider::getInstance).thenReturn(configuration);
      tempLocations.when(TempLocationManager::getInstance).thenReturn(tempManager);
      libraries.when(LibraryLoader::builder).thenReturn(builder);
      os.when(OperatingSystem::isLinux).thenReturn(true);

      assertTrue(EnvironmentChecker.checkEnvironment(new StringBuilder()));

      verify(builder).withLibraryLocation(libraryPath);
      verify(builder).withScratchDir(scratch.toString());
      verify(builder).load();
      assertTrue(Files.isDirectory(scratch));
      profiler.verifyNoInteractions();
    }
  }
}
