package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
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
        recording.close();
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
            x -> Arguments.of((x & 0x8) != 0, (x & 0x4) != 0, (x & 0x2) != 0, (x & 0x1) != 0));
  }

  @Test
  void testStartCmdEnableJMethodIDOptim() throws Exception {
    assertDoesNotThrow(
        () -> DdprofLibraryLoader.jvmAccess().getReasonNotLoaded(), "Profiler not available");

    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_JMETHODID_OPTIM_ENABLED, "true");
    DatadogProfiler profiler =
        DatadogProfiler.newInstance(ConfigProvider.withPropertiesOverride(props));

    Path dir = Paths.get("/tmp");
    Path targetFile = Files.createTempFile(dir, "target_", ".jfr");
    String cmd = profiler.cmdStartProfiling(targetFile);

    assertTrue(cmd.contains(",fjmethodid=false"), cmd);
  }

  @ParameterizedTest
  @MethodSource("wallContextFilterModes")
  void testWallScopeCompatibilityOptions(
      boolean tracingEnabled,
      boolean contextFilterEnabled,
      String expectedFilter,
      String expectedWallScope)
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
    assertTrue(cmd.contains(",filter=" + expectedFilter + ",wallscope="), cmd);
    assertTrue(cmd.contains(",wallscope=" + expectedWallScope), cmd);
    assertTrue(cmd.contains(",wallprecheck=true"), cmd);
  }

  @Test
  void testWallPrecheckIsAlwaysEnabled() throws Exception {
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
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK, "false");

    DatadogProfiler profiler =
        DatadogProfiler.newInstance(ConfigProvider.withPropertiesOverride(props));

    Path targetFile = Paths.get("/tmp/target.jfr");
    String cmd = profiler.cmdStartProfiling(targetFile);

    assertTrue(cmd.contains("wall="), cmd);
    assertTrue(cmd.contains(",wallprecheck=true"), cmd);
  }

  private static Stream<Arguments> wallContextFilterModes() {
    return Stream.of(
        Arguments.of(true, true, "0", "context"),
        Arguments.of(true, false, "", "all"),
        Arguments.of(false, true, "", "all"),
        Arguments.of(false, false, "", "all"));
  }

  @Test
  public void testContextRegistration() {
    // warning - the profiler is a process wide singleton and can't be reinitialised
    // so there is only one shot to test it here, 'foo,bar' need to be kept in the same
    // order whether in the list or the enum, and any other test which tries to register
    // context attributes will fail
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED, "true");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED, "true");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_ENABLED, "true");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED, "true");
    props.put(ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES, "foo,bar");
    DatadogProfiler profiler =
        DatadogProfiler.newInstance(ConfigProvider.withPropertiesOverride(props));
    assertTrue(profiler.setContextValue("foo", "abc"));
    assertTrue(profiler.setContextValue("bar", "abc"));
    assertTrue(profiler.setContextValue("foo", "xyz"));
    assertFalse(profiler.setContextValue("xyz", "foo"));

    DatadogProfilerContextSetter fooSetter = new DatadogProfilerContextSetter("foo", profiler);
    DatadogProfilerContextSetter barSetter = new DatadogProfilerContextSetter("bar", profiler);
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
    }

    // setSpanContext wipes all custom slots and automatically calls reapplyAppContext() to restore
    // them.
    int fooOffset = profiler.offsetOf("foo");
    fooSetter.set("reapply-me");
    assertNotEquals(0, profiler.snapshot()[fooOffset]);

    profiler.setSpanContext(1L, 1L, 0L, 1L);
    assertNotEquals(0, profiler.snapshot()[fooOffset]);

    profiler.reapplyAppContext();
    assertNotEquals(0, profiler.snapshot()[fooOffset]);

    // Scenario A: clearContextValue must clear the snapshot so reapply has nothing to restore
    profiler.clearContextValue("foo");
    assertEquals(0, profiler.snapshot()[fooOffset], "clearContextValue must clear ddprof slot");
    profiler.reapplyAppContext();
    assertEquals(
        0,
        profiler.snapshot()[fooOffset],
        "after clearContextValue, reapplyAppContext must not restore foo");

    // Scenario B: scope opened when snapshot is null — close() restores null (pre-scope state)
    {
      DatadogProfilingScope scope = new DatadogProfilingScope(profiler);
      scope.setContextValue("foo", "scope-val");
      assertNotEquals(0, profiler.snapshot()[fooOffset], "foo must be live inside scope");
      scope.close();
    }
    profiler.setSpanContext(1L, 1L, 0L, 1L);
    profiler.reapplyAppContext();
    assertEquals(
        0,
        profiler.snapshot()[fooOffset],
        "scope.close() restores pre-scope snapshot (null here), so reapply has nothing to restore");

    // Scenario B2: scope.close() immediately clears native slot — no span re-activation needed
    {
      DatadogProfilingScope scope = new DatadogProfilingScope(profiler);
      scope.setContextValue("foo", "immediate-clear-val");
      assertNotEquals(0, profiler.snapshot()[fooOffset], "foo must be live inside scope");
      scope.close();
      assertEquals(
          0,
          profiler.snapshot()[fooOffset],
          "scope.close() must immediately clear native slot without waiting for reapplyAppContext");
    }

    // Scenario B3: scope.close() immediately restores prior context to native slot
    fooSetter.set("outer-val");
    int outerEncoding = profiler.snapshot()[fooOffset];
    assertNotEquals(0, outerEncoding, "outer foo must be live before inner scope");
    {
      DatadogProfilingScope scope = new DatadogProfilingScope(profiler);
      scope.setContextValue("foo", "inner-val");
      int innerEncoding = profiler.snapshot()[fooOffset];
      assertNotEquals(outerEncoding, innerEncoding, "inner scope must change native slot");
      scope.close();
      assertEquals(
          outerEncoding,
          profiler.snapshot()[fooOffset],
          "scope.close() must immediately restore prior native slot value");
    }
    profiler.clearContextValue("foo");

    // Scenario C: reapplyAppContext is idempotent
    fooSetter.set("idempotent-value");
    profiler.setSpanContext(1L, 1L, 0L, 1L);
    profiler.reapplyAppContext();
    int afterFirst = profiler.snapshot()[fooOffset];
    assertNotEquals(0, afterFirst, "reapplyAppContext must restore foo");
    profiler.reapplyAppContext();
    assertEquals(
        afterFirst,
        profiler.snapshot()[fooOffset],
        "calling reapplyAppContext twice must produce the same result");

    // Scenario D: re-activation after child activation restores app attr
    int parentEncoding = profiler.snapshot()[fooOffset];
    assertNotEquals(0, parentEncoding, "foo must be set before child activation");
    profiler.setSpanContext(2L, 2L, 0L, 2L);
    assertEquals(
        parentEncoding,
        profiler.snapshot()[fooOffset],
        "setSpanContext auto-reapplies app context on child activation");
    profiler.setSpanContext(1L, 1L, 0L, 1L);
    profiler.reapplyAppContext();
    assertEquals(
        parentEncoding,
        profiler.snapshot()[fooOffset],
        "re-activation + reapply must restore parent app attr");

    // Scenario E: app attr set in child survives into next activation
    profiler.setSpanContext(2L, 2L, 0L, 2L);
    fooSetter.set("child-val");
    int childEncoding = profiler.snapshot()[fooOffset];
    assertNotEquals(0, childEncoding, "foo must be set in child context");
    profiler.setSpanContext(1L, 1L, 0L, 1L);
    assertEquals(
        childEncoding,
        profiler.snapshot()[fooOffset],
        "setSpanContext auto-reapplies app context (child-val) on re-activation");
    profiler.reapplyAppContext();
    assertEquals(
        childEncoding,
        profiler.snapshot()[fooOffset],
        "ThreadLocal ambient value must survive into the next activation");

    // Scenario F: app attributes are visible after the last span scope closes.
    // clearSpanContext() wipes all custom slots and automatically calls reapplyAppContext(),
    // restoring app attrs immediately.
    fooSetter.set("pre-close-val");
    assertNotEquals(0, profiler.snapshot()[fooOffset], "foo must be live before clearSpanContext");
    profiler.clearSpanContext();
    assertNotEquals(
        0, profiler.snapshot()[fooOffset], "clearSpanContext must auto-reapply app context");
    profiler.reapplyAppContext();
    assertNotEquals(
        0,
        profiler.snapshot()[fooOffset],
        "reapplyAppContext after clearSpanContext must restore foo");

    // Scenario G: no app value set — clearSpanContext + reapplyAppContext leaves slot empty
    profiler.clearContextValue("foo");
    profiler.clearAppContextSnapshot();
    profiler.clearSpanContext();
    profiler.reapplyAppContext();
    assertEquals(
        0,
        profiler.snapshot()[fooOffset],
        "reapplyAppContext with no snapshot must leave foo at 0");

    // Scenario H: scope.close() restores ambient context set before scope was opened
    fooSetter.set("ambient-val");
    // Activate a span so reapplyAppContext can write the value (validOffset=1 after
    // setSpanContext).
    profiler.setSpanContext(1L, 1L, 0L, 1L);
    profiler.reapplyAppContext();
    int ambientEncoding = profiler.snapshot()[fooOffset];
    assertNotEquals(0, ambientEncoding, "ambient foo must be live");
    {
      DatadogProfilingScope scope = new DatadogProfilingScope(profiler);
      scope.setContextValue("foo", "scope-override");
      assertNotEquals(
          ambientEncoding, profiler.snapshot()[fooOffset], "scope must override ambient");
      scope.close(); // must restore ambient snapshot, not nuke it
    }
    profiler.setSpanContext(1L, 1L, 0L, 1L);
    profiler.reapplyAppContext();
    assertEquals(
        ambientEncoding,
        profiler.snapshot()[fooOffset],
        "scope.close() must restore ambient context, not clear it");

    // Clean up after Scenario H so Acceptance tests start from a neutral state.
    profiler.clearContextValue("foo");
    profiler.clearSpanContext();

    // Acceptance 1: reapply happens automatically inside setSpanContext — no manual call needed.
    fooSetter.set("auto-reapply-val");
    assertNotEquals(0, profiler.snapshot()[fooOffset], "foo must be live before setSpanContext");
    profiler.setSpanContext(1L, 1L, 0L, 1L);
    // Deliberately NO profiler.reapplyAppContext() here.
    assertNotEquals(
        0,
        profiler.snapshot()[fooOffset],
        "Acceptance 1: setSpanContext must auto-restore app slot without explicit reapplyAppContext");
    profiler.clearContextValue("foo");

    // Acceptance 2: reapply happens automatically inside clearSpanContext — no manual call needed.
    fooSetter.set("clear-reapply-val");
    assertNotEquals(0, profiler.snapshot()[fooOffset], "foo must be live before clearSpanContext");
    profiler.clearSpanContext();
    // Deliberately NO profiler.reapplyAppContext() here.
    assertNotEquals(
        0,
        profiler.snapshot()[fooOffset],
        "Acceptance 2: clearSpanContext must auto-restore app slot without explicit reapplyAppContext");
    profiler.clearContextValue("foo");

    // Acceptance 3: no app value set — clearSpanContext leaves slot empty.
    profiler.clearContextValue("foo");
    profiler.clearAppContextSnapshot();
    profiler.clearSpanContext();
    // Deliberately NO profiler.reapplyAppContext() here.
    assertEquals(
        0,
        profiler.snapshot()[fooOffset],
        "Acceptance 3: clearSpanContext with no app value must leave foo at 0");

    // Acceptance 4: nonZeroCount accuracy — cleared snapshot is considered empty so a new
    // scope's save/restore does not leak a stale entry.
    fooSetter.set("v1");
    assertNotEquals(0, profiler.snapshot()[fooOffset], "foo must be live after set");
    profiler.clearContextValue("foo");
    assertEquals(0, profiler.snapshot()[fooOffset], "foo must be 0 after clearContextValue");
    {
      DatadogProfilingScope scope4 = new DatadogProfilingScope(profiler);
      scope4.setContextValue("foo", "v2");
      assertNotEquals(0, profiler.snapshot()[fooOffset], "foo must be live inside scope4");
      scope4.close();
    }
    assertEquals(
        0,
        profiler.snapshot()[fooOffset],
        "Acceptance 4: scope.close() must restore pre-scope empty state; nonZeroCount must not drift");

    // Acceptance 5: clearAppContextSnapshot() fully resets per-thread state so no stale value
    // leaks through.
    fooSetter.set("leak-check");
    assertNotEquals(0, profiler.snapshot()[fooOffset], "foo must be live before reset");
    profiler.clearContextValue("foo");
    profiler.clearAppContextSnapshot();
    {
      DatadogProfilingScope scope5 = new DatadogProfilingScope(profiler);
      scope5.setContextValue("foo", "after-reset");
      assertNotEquals(0, profiler.snapshot()[fooOffset], "foo must be live inside scope5");
      scope5.close();
    }
    profiler.setSpanContext(1L, 1L, 0L, 1L);
    // Deliberately NO profiler.reapplyAppContext() here — fold-in suffices.
    assertEquals(
        0,
        profiler.snapshot()[fooOffset],
        "Acceptance 5: after clearAppContextSnapshot, scope.close() restores empty state; no stale value leaks");

    // Acceptance 6: restoreAppContext does not throw when scope stack is absent on the restoring
    // thread. The guard is verified by the absence of an exception on the normal close path.
    profiler.clearSpanContext();
    profiler.clearContextValue("foo");
    profiler.clearAppContextSnapshot();
    assertDoesNotThrow(
        () -> {
          DatadogProfilingScope scope6 = new DatadogProfilingScope(profiler);
          scope6.setContextValue("foo", "guard-val");
          scope6.close();
        },
        "Acceptance 6: DatadogProfilingScope.close() must not throw even when scopeStack is absent");
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
