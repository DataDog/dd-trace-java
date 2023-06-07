package com.datadog.profiling.controller.openjdk;

import static com.datadog.profiling.controller.ProfilingSupport.*;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.jfr.JfpUtilsTest;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";

  @Test
  public void testCreateContinuousRecording() throws Exception {
    Properties props = new Properties();
    props.put(PROFILING_TEMPLATE_OVERRIDE_FILE, JfpUtilsTest.OVERRIDES);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      assertEquals(TEST_NAME, recording.getName());
      assertEquals(controller.getMaxSize(), recording.getMaxSize());
      assertEquals(OpenJdkController.RECORDING_MAX_AGE, recording.getMaxAge());
    }
  }

  @Test
  public void testHeapProfilerIsDisabledOnUnsupportedVersion() throws Exception {
    Properties props = new Properties();
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      assertEquals(
          isOldObjectSampleAvailable(),
          Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
    }
  }

  @Test
  public void testHeapProfilerIsStillOverriddenOnUnsupportedVersion() throws Exception {
    Properties props = new Properties();
    props.put(PROFILING_TEMPLATE_OVERRIDE_FILE, JfpUtilsTest.OVERRIDES_OLD_OBJECT_SAMPLE);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
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
    Properties props = new Properties();
    props.put(PROFILING_HEAP_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData) controller.createRecording(TEST_NAME).stop()).getRecording()) {
      if (!isOldObjectSampleAvailable()) {
        assertEquals(
            true, Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
      }
    }
  }

  @Test
  public void testAllocationProfilerIsDisabledOnUnsupportedVersion() throws Exception {
    Properties props = new Properties();
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
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
    Properties props = new Properties();
    props.put(PROFILING_TEMPLATE_OVERRIDE_FILE, JfpUtilsTest.OVERRIDES_OBJECT_ALLOCATION);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
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
    Properties props = new Properties();
    props.put(PROFILING_ALLOCATION_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData) controller.createRecording(TEST_NAME).stop()).getRecording()) {
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
    Assumptions.assumeFalse(isNativeMethodSampleAvailable());
    Properties props = new Properties();
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      assertFalse(
          Boolean.parseBoolean(recording.getSettings().get("jdk.NativeMethodSample#enabled")));
    }
  }

  @Test
  public void testNativeProfilerIsStillOverriddenOnUnsupportedVersion() throws Exception {
    Properties props = new Properties();
    props.put(PROFILING_TEMPLATE_OVERRIDE_FILE, JfpUtilsTest.OVERRIDES_NATIVE_METHOD_SAMPLE);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!isNativeMethodSampleAvailable()) {
        assertTrue(
            Boolean.parseBoolean(recording.getSettings().get("jdk.NativeMethodSample#enabled")));
      }
    }
  }
}
