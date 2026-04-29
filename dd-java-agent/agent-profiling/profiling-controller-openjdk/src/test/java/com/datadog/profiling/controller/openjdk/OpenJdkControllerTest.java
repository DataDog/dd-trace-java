package com.datadog.profiling.controller.openjdk;

import static com.datadog.profiling.controller.ProfilingSupport.isNativeMethodSampleAvailable;
import static com.datadog.profiling.controller.ProfilingSupport.isObjectAllocationSampleAvailable;
import static com.datadog.profiling.controller.ProfilingSupport.isOldObjectSampleAvailable;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AUXILIARY_TYPE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AUXILIARY_TYPE_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_CPU_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_IO_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_LOCK_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_THREAD_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_WALLTIME_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.jfr.JfpUtilsTest;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.EnumSet;
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
    // Disable ddprof so OldObjectSample is not proactively disabled
    props.put(PROFILING_DATADOG_PROFILER_ENABLED, "false");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      // On JVMs where OldObjectSample is not available (e.g. Java 8), explicitly enabling heap
      // profiling has no effect — the event cannot be safely enabled.
      assertEquals(
          isOldObjectSampleAvailable(),
          Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
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
  public void testOldObjectSampleDisabledWhenDdprofMemleakActive() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_DATADOG_PROFILER_ENABLED, "true");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    ControllerContext context = new ControllerContext();
    context.setDatadogProfilerEnabled(true);
    context.setDatadogProfilingModes(EnumSet.of(ProfilingMode.MEMLEAK));

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData) controller.createRecording(TEST_NAME, context.snapshot()).stop())
            .getRecording()) {
      assertFalse(Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
    }
  }

  @Test
  public void testUnifiedFlagDisabledTurnsOffOldObjectSample() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_HEAP_ENABLED, "false");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data =
        controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")),
          "OldObjectSample should be disabled when unified live heap flag is false");
    }
  }

  @Test
  public void testCpuGateDisablesCpuEvents() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_CPU_ENABLED, "false");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.ExecutionSample#enabled")),
          "ExecutionSample must be disabled when CPU profiling is disabled");
    }
  }

  @Test
  public void testExceptionGateDisablesExceptionEvents() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_EXCEPTION_ENABLED, "false");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("datadog.ExceptionSample#enabled")),
          "ExceptionSample must be disabled when exception profiling is disabled");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("datadog.ExceptionCount#enabled")),
          "ExceptionCount must be disabled when exception profiling is disabled");
    }
  }

  @Test
  public void testIoGateDisablesIoEvents() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_IO_ENABLED, "false");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.FileRead#enabled")),
          "FileRead must be disabled when I/O profiling is disabled");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.SocketRead#enabled")),
          "SocketRead must be disabled when I/O profiling is disabled");
    }
  }

  @Test
  public void testLockGateDisablesLockEvents() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_LOCK_ENABLED, "false");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.JavaMonitorEnter#enabled")),
          "JavaMonitorEnter must be disabled when lock profiling is disabled");
    }
  }

  @Test
  public void testThreadGateDisablesThreadEvents() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_THREAD_ENABLED, "false");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.ThreadStart#enabled")),
          "ThreadStart must be disabled when thread profiling is disabled");
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.ThreadEnd#enabled")),
          "ThreadEnd must be disabled when thread profiling is disabled");
    }
  }

  @Test
  public void testWallGateDoesNotDisableTimelineEvents() throws Exception {
    Properties props = getConfigProperties();
    props.put(PROFILING_WALLTIME_ENABLED, "false");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData)
                controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop())
            .getRecording()) {
      // JavaMonitorWait and ThreadPark serve timeline purposes beyond wall-clock profiling and must
      // NOT be disabled when only wall profiling is disabled. (ThreadSleep is disabled by the
      // dd.jfp template by default and is therefore not checked here.)
      assertTrue(
          Boolean.parseBoolean(recording.getSettings().get("jdk.JavaMonitorWait#enabled")),
          "JavaMonitorWait must remain enabled when only wall profiling is disabled");
      assertTrue(
          Boolean.parseBoolean(recording.getSettings().get("jdk.ThreadPark#enabled")),
          "ThreadPark must remain enabled when only wall profiling is disabled");
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
