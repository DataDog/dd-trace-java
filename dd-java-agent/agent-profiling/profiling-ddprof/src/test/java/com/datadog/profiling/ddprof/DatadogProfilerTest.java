package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.environment.OperatingSystem;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DatadogProfilerTest {
  private static final Logger log = LoggerFactory.getLogger(DatadogProfilerTest.class);

  @BeforeEach
  public void setup() {
    Assumptions.assumeTrue(OperatingSystem.isLinux());
  }

  @Test
  void test() throws Exception {
    assertDoesNotThrow(
        () -> DdprofLibraryLoader.jvmAccess().getReasonNotLoaded(), "Profiler not available");
    DatadogProfiler profiler = DatadogProfiler.newInstance(ConfigProvider.getInstance());
    assertFalse(profiler.enabledModes().isEmpty());

    if (profiler.isActive()) {
      // apparently the CI is already running with Datadog profiler attached (?)
      log.warn("Datadog profiler is already running. Skipping the test.");
      return;
    }

    OngoingRecording recording = profiler.start();
    if (recording != null) {
      try {
        long cumulative = 0L;
        for (int i = 0; i < 5000; i++) {
          cumulative ^= UUID.randomUUID().getLeastSignificantBits();
        }
        log.info("calculated: {}", cumulative);
        RecordingData data = profiler.stop(recording);
        assertNotNull(data);
        IItemCollection events = JfrLoaderToolkit.loadEvents(data.getStream());
        assertTrue(events.hasItems());
      } finally {
        recording.stop();
      }
    } else {
      log.warn("Datadog Profiler is not available. Skipping test.");
    }
  }

  @ParameterizedTest
  @MethodSource("profilingModes")
  void testStartCmd(boolean cpu, boolean wall, boolean alloc, boolean memleak) throws Exception {
    assertDoesNotThrow(
        () -> DdprofLibraryLoader.jvmAccess().getReasonNotLoaded(), "Profiler not available");
    DatadogProfiler profiler =
        DatadogProfiler.newInstance(configProvider(cpu, wall, alloc, memleak));

    Path targetFile = Paths.get("/tmp/target.jfr");
    String cmd = profiler.cmdStartProfiling(targetFile);

    if (profiler.enabledModes().contains(ProfilingMode.CPU)) {
      assertTrue(cmd.contains("cpu="), cmd);
    }
    if (profiler.enabledModes().contains(ProfilingMode.WALL)) {
      assertTrue(cmd.contains("wall="), cmd);
    }
    if (profiler.enabledModes().contains(ProfilingMode.ALLOCATION)) {
      assertTrue(cmd.matches(".*?memory=[0-9]+b?:.*?a.*"), cmd);
    }
    if (profiler.enabledModes().contains(ProfilingMode.MEMLEAK)) {
      assertTrue(cmd.matches(".*?memory=[0-9]+b?:.*?[lL].*"), cmd);
    }
  }

  private static Stream<Arguments> profilingModes() {
    return IntStream.range(0, 1 << 4)
        .mapToObj(
            x ->
                Arguments.of((x & 0x1000) != 0, (x & 0x100) != 0, (x & 0x10) != 0, (x & 0x1) != 0));
  }

  @ParameterizedTest
  @MethodSource("wallContextFilterModes")
  void testWallContextFilter(boolean tracingEnabled, boolean contextFilterEnabled)
      throws Exception {
    // Skip test if profiler native library is not available (e.g., on macOS)
    try {
      Throwable reason = DdprofLibraryLoader.jvmAccess().getReasonNotLoaded();
      if (reason != null) {
        Assumptions.assumeTrue(false, "Profiler not available: " + reason.getMessage());
      }
    } catch (Throwable e) {
      Assumptions.assumeTrue(false, "Profiler not available: " + e.getMessage());
    }

    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED, "true");
    props.put(TraceInstrumentationConfig.TRACE_ENABLED, Boolean.toString(tracingEnabled));
    props.put(
        ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER,
        Boolean.toString(contextFilterEnabled));

    DatadogProfiler profiler =
        DatadogProfiler.newInstance(ConfigProvider.withPropertiesOverride(props));

    Path targetFile = Paths.get("/tmp/target.jfr");
    String cmd = profiler.cmdStartProfiling(targetFile);

    assertTrue(cmd.contains("wall="), "Command should contain wall profiling: " + cmd);

    if (tracingEnabled && contextFilterEnabled) {
      assertTrue(
          cmd.contains(",filter=0"),
          "Command should contain ',filter=0' when tracing and context filter are enabled: " + cmd);
    } else {
      assertTrue(
          cmd.contains(",filter="),
          "Command should contain ',filter=' when tracing is disabled or context filter is disabled: "
              + cmd);
      if (cmd.contains(",filter=0")) {
        throw new AssertionError(
            "Command should not contain ',filter=0' when tracing is disabled or context filter is disabled: "
                + cmd);
      }
    }
  }

  private static Stream<Arguments> wallContextFilterModes() {
    return Stream.of(
        Arguments.of(true, true), // tracing enabled, context filter enabled -> filter=0
        Arguments.of(true, false), // tracing enabled, context filter disabled -> filter=
        Arguments.of(
            false, true), // tracing disabled, context filter enabled -> filter= (tracing disabled
        // overrides)
        Arguments.of(false, false) // tracing disabled, context filter disabled -> filter=
        );
  }

  @Test
  public void testContextRegistration() {
    // warning - the profiler is a process wide singleton and can't be reinitialised
    // so there is only one shot to test it here, 'foo,bar' need to be kept in the same
    // order whether in the list or the enum, and any other test which tries to register
    // context attributes will fail
    DatadogProfiler profiler =
        new DatadogProfiler(
            configProvider(true, true, true, true), new HashSet<>(Arrays.asList("foo", "bar")));
    assertTrue(profiler.setContextValue("foo", "abc"));
    assertTrue(profiler.setContextValue("bar", "abc"));
    assertTrue(profiler.setContextValue("foo", "xyz"));
    assertFalse(profiler.setContextValue("xyz", "foo"));

    DatadogProfilerContextSetter fooSetter = new DatadogProfilerContextSetter("foo", profiler);
    DatadogProfilerContextSetter barSetter = new DatadogProfilerContextSetter("bar", profiler);
    int[] snapshot0 = profiler.snapshot();
    try (ProfilingScope ignored = new DatadogProfilingScope(profiler)) {
      fooSetter.set("foo0");
      barSetter.set("bar0");
      int[] snapshot1 = profiler.snapshot();
      try (ProfilingScope inner = new DatadogProfilingScope(profiler)) {
        fooSetter.set("foo1");
        barSetter.set("bar1");
        assertFalse(Arrays.equals(snapshot1, profiler.snapshot()));
        int[] snapshot2 = profiler.snapshot();
        inner.setContextValue("foo", "foo2");
        inner.setContextValue("bar", "bar2");
        assertFalse(Arrays.equals(snapshot2, profiler.snapshot()));
      }
      assertArrayEquals(snapshot1, profiler.snapshot());
    }
    assertArrayEquals(snapshot0, profiler.snapshot());
  }

  private static ConfigProvider configProvider(
      boolean cpu, boolean wall, boolean alloc, boolean memleak) {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED, Boolean.toString(cpu));
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED, Boolean.toString(wall));
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_ENABLED, Boolean.toString(alloc));
    props.put(
        ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED, Boolean.toString(memleak));
    return ConfigProvider.withPropertiesOverride(props);
  }
}
