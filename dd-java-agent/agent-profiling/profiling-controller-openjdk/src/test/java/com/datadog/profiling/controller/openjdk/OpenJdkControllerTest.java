package com.datadog.profiling.controller.openjdk;

import static com.datadog.profiling.controller.ProfilingSupport.isNativeMethodSampleAvailable;
import static com.datadog.profiling.controller.ProfilingSupport.isObjectAllocationSampleAvailable;
import static com.datadog.profiling.controller.ProfilingSupport.isOldObjectSampleAvailable;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOC_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AUXILIARY_TYPE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AUXILIARY_TYPE_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_CPU_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DISABLED_EVENTS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_LATENCY_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_LIVEHEAP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.jfr.JfpUtilsTest;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import jdk.jfr.Recording;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";

  @BeforeAll
  static void setupSpec() {
    assumeFalse(JavaVirtualMachine.isJ9());
  }

  @Test
  public void testCreateContinuousRecording() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_DATADOG_PROFILER_ENABLED, "false");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data =
        controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      assertEquals(TEST_NAME, recording.getName());
      assertEquals(controller.getMaxSize(), recording.getMaxSize());
      assertEquals(OpenJdkController.RECORDING_MAX_AGE, recording.getMaxAge());
    }
  }

  @Test
  public void testHeapProfilerIsDisabledOnUnsupportedVersion() throws Exception {
    Properties props = getConfigProperties();

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data =
        controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      assertEquals(
          isOldObjectSampleAvailable(),
          Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
    }
  }

  @Test
  public void testHeapProfilerIsStillOverriddenOnUnsupportedVersion() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_TEMPLATE_OVERRIDE_FILE, JfpUtilsTest.OVERRIDES_OLD_OBJECT_SAMPLE);

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data =
        controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!isOldObjectSampleAvailable()) {
        assertEquals(
            true, Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
      }
    }
  }

  @Test
  public void testHeapProfilerIsStillOverriddenThroughConfig() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_HEAP_ENABLED, "true");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      if (!isOldObjectSampleAvailable()) {
        assertEquals(
            true, Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
      }
    }
  }

  @Test
  public void testAllocationProfilerIsDisabledOnUnsupportedVersion() throws Exception {
    Properties props = getConfigProperties();

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data =
        controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (isObjectAllocationSampleAvailable()) {
        assertEquals(
            false,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationInNewTLAB#enabled")));
        assertEquals(
            false,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationOutsideTLAB#enabled")));
        assertEquals(
            true,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationSample#enabled")));
      } else {
        assertEquals(
            false,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationInNewTLAB#enabled")));
        assertEquals(
            false,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationOutsideTLAB#enabled")));
        assertEquals(
            false,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationSample#enabled")));
      }
    }
  }

  @Test
  public void testAllocationProfilerIsStillOverriddenOnUnsupportedVersion() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_TEMPLATE_OVERRIDE_FILE, JfpUtilsTest.OVERRIDES_OBJECT_ALLOCATION);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data =
        controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!isObjectAllocationSampleAvailable()) {
        assertEquals(
            true,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationInNewTLAB#enabled")));
        assertEquals(
            true,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationOutsideTLAB#enabled")));
      }
    }
  }

  @Test
  public void testAllocationProfilerIsStillOverriddenThroughConfig() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_ALLOCATION_ENABLED, "true");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      if (!isObjectAllocationSampleAvailable()) {
        assertEquals(
            true,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationInNewTLAB#enabled")));
        assertEquals(
            true,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationOutsideTLAB#enabled")));
      }
    }
  }

  @Test
  public void testNativeProfilerIsDisabledOnUnsupportedVersion() throws Exception {
    assumeFalse(isNativeMethodSampleAvailable());
    Properties props = getConfigProperties();

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data =
        controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.NativeMethodSample#enabled")));
    }
  }

  @Test
  public void testNativeProfilerIsStillOverriddenOnUnsupportedVersion() throws Exception {
    Properties props = getConfigProperties();

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data =
        controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!isNativeMethodSampleAvailable()) {
        assertTrue(
            Boolean.parseBoolean(recording.getSettings().get("jdk.NativeMethodSample#enabled")));
      }
    }
  }

  @Test
  public void testCpuUmbrellaDisablesTurnOffCpuEvents() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_CPU_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.ExecutionSample#enabled")),
          "ExecutionSample should be disabled when profiling.cpu.enabled=false");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.CPUTimeSample#enabled")),
          "CPUTimeSample should be disabled when profiling.cpu.enabled=false");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.CPUTimeSamplesLost#enabled")),
          "CPUTimeSamplesLost should be disabled when profiling.cpu.enabled=false");
    }
  }

  @Test
  public void testLatencyUmbrellaDisablesLatencyEvents() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_LATENCY_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.JavaMonitorEnter#enabled")),
          "JavaMonitorEnter should be disabled when profiling.latency.enabled=false");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.JavaMonitorWait#enabled")),
          "JavaMonitorWait should be disabled when profiling.latency.enabled=false");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.JavaMonitorInflate#enabled")),
          "JavaMonitorInflate should be disabled when profiling.latency.enabled=false");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.FileRead#enabled")),
          "FileRead should be disabled when profiling.latency.enabled=false");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.FileWrite#enabled")),
          "FileWrite should be disabled when profiling.latency.enabled=false");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.SocketRead#enabled")),
          "SocketRead should be disabled when profiling.latency.enabled=false");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.SocketWrite#enabled")),
          "SocketWrite should be disabled when profiling.latency.enabled=false");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.ThreadStart#enabled")),
          "ThreadStart should be disabled when profiling.latency.enabled=false");
    }
  }

  @Test
  public void testLiveheapUmbrellaFalseDisablesOldObjectSample() throws Exception {
    assumeTrue(isOldObjectSampleAvailable());
    Properties props = getConfigProperties();
    props.put(PROFILING_LIVEHEAP_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")),
          "OldObjectSample should be disabled when profiling.liveheap.enabled=false");
    }
  }

  @Test
  public void testLiveheapUmbrellaTrueEnablesOldObjectSampleWhenDdprofDisabled() throws Exception {
    Properties props = getConfigProperties();
    // ddprof disabled → JFR must handle liveheap
    props.put(PROFILING_LIVEHEAP_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertTrue(
          Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")),
          "OldObjectSample should be enabled when profiling.liveheap.enabled=true and ddprof disabled");
    }
  }

  @Test
  public void testHeapEnabledFalseOverridesLiveheapUmbrella() throws Exception {
    assumeTrue(isOldObjectSampleAvailable());
    Properties props = getConfigProperties();
    // Explicit JFR override takes precedence over umbrella
    props.put(PROFILING_LIVEHEAP_ENABLED, "true");
    props.put(PROFILING_HEAP_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")),
          "profiling.heap.enabled=false should override profiling.liveheap.enabled=true");
    }
  }

  @Test
  public void testAllocUmbrellaDisablesAllocationOnModernJdk() throws Exception {
    assumeTrue(isObjectAllocationSampleAvailable());
    Properties props = getConfigProperties();
    props.put(PROFILING_ALLOC_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.ObjectAllocationSample#enabled")),
          "ObjectAllocationSample should be disabled when profiling.alloc.enabled=false");
    }
  }

  @Test
  public void testAllocationEnabledOverridesAllocUmbrella() throws Exception {
    assumeTrue(isObjectAllocationSampleAvailable());
    Properties props = getConfigProperties();
    // Explicit JFR-level override takes precedence over umbrella
    props.put(PROFILING_ALLOC_ENABLED, "false");
    props.put(PROFILING_ALLOCATION_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertTrue(
          Boolean.parseBoolean(recording.getSettings().get("jdk.ObjectAllocationSample#enabled")),
          "profiling.allocation.enabled=true should override profiling.alloc.enabled=false");
    }
  }

  @Test
  public void testExceptionUmbrellaDisablesExceptionSample() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_EXCEPTION_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("datadog.ExceptionSample#enabled")),
          "datadog.ExceptionSample should be disabled when profiling.exception.enabled=false");
    }
  }

  @Test
  public void testCpuUmbrellaTrueReEnablesCpuEvents() throws Exception {
    Properties props = getConfigProperties();
    // First disable CPU events via disabled.events, then re-enable via umbrella
    props.put(PROFILING_DISABLED_EVENTS, "jdk.ExecutionSample");
    props.put(PROFILING_CPU_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertTrue(
          Boolean.parseBoolean(recording.getSettings().get("jdk.ExecutionSample#enabled")),
          "ExecutionSample should be re-enabled when profiling.cpu.enabled=true");
    }
  }

  @Test
  public void testLatencyUmbrellaTrueReEnablesLatencyEvents() throws Exception {
    Properties props = getConfigProperties();
    // First disable latency events via disabled.events, then re-enable via umbrella
    props.put(PROFILING_DISABLED_EVENTS, "jdk.JavaMonitorEnter,jdk.FileRead");
    props.put(PROFILING_LATENCY_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertTrue(
          Boolean.parseBoolean(recording.getSettings().get("jdk.JavaMonitorEnter#enabled")),
          "JavaMonitorEnter should be re-enabled when profiling.latency.enabled=true");
      assertTrue(
          Boolean.parseBoolean(recording.getSettings().get("jdk.FileRead#enabled")),
          "FileRead should be re-enabled when profiling.latency.enabled=true");
    }
  }

  @Test
  public void testExceptionUmbrellaTrueReEnablesExceptionSample() throws Exception {
    Properties props = getConfigProperties();
    // First disable exception event via disabled.events, then re-enable via umbrella
    props.put(PROFILING_DISABLED_EVENTS, "datadog.ExceptionSample");
    props.put(PROFILING_EXCEPTION_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertTrue(
          Boolean.parseBoolean(recording.getSettings().get("datadog.ExceptionSample#enabled")),
          "datadog.ExceptionSample should be re-enabled when profiling.exception.enabled=true");
    }
  }

  @Test
  public void testDefaultsPreservedWhenNoUmbrellaSet() throws Exception {
    // Verify that JFR event defaults remain unchanged when no umbrella properties are set
    Properties props = getConfigProperties();
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      // CPU events should follow template defaults (enabled)
      assertTrue(
          Boolean.parseBoolean(recording.getSettings().get("jdk.ExecutionSample#enabled"))
              || Boolean.parseBoolean(recording.getSettings().get("jdk.CPUTimeSample#enabled")),
          "CPU sampling event should be enabled by default");
      // OldObjectSample should match JVM support
      assertEquals(
          isOldObjectSampleAvailable(),
          Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")),
          "OldObjectSample should follow JVM support when no umbrella set");
      // Latency events should be enabled by default (from template)
      assertTrue(
          Boolean.parseBoolean(recording.getSettings().get("jdk.JavaMonitorEnter#enabled")),
          "JavaMonitorEnter should be enabled by default");
    }
  }

  private static Properties getConfigProperties() {
    Properties props = new Properties();
    // make sure the async profiler is not force-enabled
    props.put(PROFILING_AUXILIARY_TYPE, PROFILING_AUXILIARY_TYPE_DEFAULT);
    props.put(PROFILING_DATADOG_PROFILER_ENABLED, "false");
    return props;
  }
}
