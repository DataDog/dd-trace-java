package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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

  @Test
  void test() throws Exception {
    DatadogProfiler profiler = DatadogProfiler.newInstance(ConfigProvider.getInstance());
    if (!profiler.isAvailable()) {
      log.warn("Datadog Profiler not available. Skipping test.");
      return;
    }
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
    DatadogProfiler profiler =
        DatadogProfiler.newInstance(configProvider(cpu, wall, alloc, memleak));
    if (!profiler.isAvailable()) {
      log.warn("Datadog Profiler not available. Skipping test.");
      return;
    }

    Path targetFile = Paths.get("/tmp/target.jfr");
    String cmd = profiler.cmdStartProfiling(targetFile);

    if (profiler.enabledModes().contains(ProfilingMode.CPU)) {
      assertTrue(cmd.contains("cpu="));
    }
    if (profiler.enabledModes().contains(ProfilingMode.WALL)) {
      assertTrue(cmd.contains("wall="));
    }
    if (profiler.enabledModes().contains(ProfilingMode.ALLOCATION)) {
      assertTrue(cmd.contains("alloc="));
    }
    if (profiler.enabledModes().contains(ProfilingMode.MEMLEAK)) {
      assertTrue(cmd.contains("memleak="));
    }
  }

  private static Stream<Arguments> profilingModes() {
    return IntStream.range(0, 1 << 4)
        .mapToObj(
            x ->
                Arguments.of((x & 0x1000) != 0, (x & 0x100) != 0, (x & 0x10) != 0, (x & 0x1) != 0));
  }

  @Test
  public void testContextRegistration() throws UnsupportedEnvironmentException {
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
    assertTrue(profiler.setContextValue(ContextEnum.FOO, "abc"));
    assertTrue(profiler.setContextValue(ContextEnum.BAR, "abc"));
    assertTrue(profiler.setContextValue(ContextEnum.FOO, "xyz"));
  }

  @Test
  public void testGetEnumConstants() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_CONTEXT_ENUM, ContextEnum.class.getName());
    Set<String> attributes =
        DatadogProfilerConfig.getContextAttributes(ConfigProvider.withPropertiesOverride(props));
    assertEquals(ContextEnum.values().length, attributes.size());
    Iterator<String> it = attributes.iterator();
    for (ContextEnum attr : ContextEnum.values()) {
      assertEquals(attr.toString(), it.next());
    }
  }

  private static ConfigProvider configProvider(
      boolean cpu, boolean wall, boolean alloc, boolean memleak) {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED, Boolean.toString(cpu));
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED, Boolean.toString(wall));
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_ENABLED, Boolean.toString(alloc));
    props.put(
        ProfilingConfig.PROFILING_DATADOG_PROFILER_MEMLEAK_ENABLED, Boolean.toString(memleak));
    return ConfigProvider.withPropertiesOverride(props);
  }
}
