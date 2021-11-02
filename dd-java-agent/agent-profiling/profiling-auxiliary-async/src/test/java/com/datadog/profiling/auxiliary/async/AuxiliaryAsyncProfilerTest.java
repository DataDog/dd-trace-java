package com.datadog.profiling.auxiliary.async;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.auxiliary.ProfilingMode;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AuxiliaryAsyncProfilerTest {
  private static final Logger log = LoggerFactory.getLogger(AuxiliaryAsyncProfilerTest.class);

  @Test
  void test() throws Exception {
    AuxiliaryAsyncProfiler profiler = new AuxiliaryAsyncProfiler(ConfigProvider.getInstance());
    if (!profiler.isAvailable()) {
      log.warn("Async Profiler not available. Skipping test.");
      return;
    }
    assertFalse(profiler.enabledModes().isEmpty());

    if (profiler.isActive()) {
      // apparently the CI is already running with async profiler attached (?)
      log.warn("Async profiler is already running. Skipping the test.");
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
      log.warn("Async Profiler is not available. Skipping test.");
    }
  }

  @Test
  void testProvider() {
    AuxiliaryAsyncProfiler.ImplementerProvider provider =
        new AuxiliaryAsyncProfiler.ImplementerProvider();
    assertTrue(provider.canProvide(AuxiliaryAsyncProfiler.TYPE));
    assertFalse(provider.canProvide(null));
    assertFalse(provider.canProvide(""));
    assertFalse(provider.canProvide("unknown"));

    assertNotNull(provider.provide(ConfigProvider.getInstance()));
  }

  @ParameterizedTest
  @MethodSource("profilingModes")
  void testStartCmd(boolean cpu, boolean alloc) {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_ASYNC_CPU_ENABLED, Boolean.toString(cpu));
    props.put(ProfilingConfig.PROFILING_ASYNC_ALLOC_ENABLED, Boolean.toString(alloc));

    AuxiliaryAsyncProfiler profiler =
        new AuxiliaryAsyncProfiler(ConfigProvider.withPropertiesOverride(props));

    Path targetFile = Paths.get("/tmp/target.jfr");
    String cmd = profiler.cmdStartProfiling(targetFile);

    if (profiler.enabledModes().contains(ProfilingMode.CPU)) {
      assertTrue(cmd.contains("event=itimer"));
    }
    if (profiler.enabledModes().contains(ProfilingMode.ALLOCATION)) {
      assertTrue(cmd.contains("event=alloc") || cmd.contains("alloc="));
    }
  }

  private static Stream<Arguments> profilingModes() {
    return Stream.of(
        Arguments.of(false, false),
        Arguments.of(false, true),
        Arguments.of(true, false),
        Arguments.of(true, true));
  }
}
