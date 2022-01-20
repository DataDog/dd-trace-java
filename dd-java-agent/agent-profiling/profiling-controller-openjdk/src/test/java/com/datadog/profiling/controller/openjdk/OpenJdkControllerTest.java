package com.datadog.profiling.controller.openjdk;

import static datadog.trace.api.Platform.isJavaVersion;
import static datadog.trace.api.Platform.isJavaVersionAtLeast;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.jfr.JfpUtilsTest;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";

  @Test
  public void testCreateContinuousRecording() throws Exception {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE, JfpUtilsTest.OVERRIDES);
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
  public void testOldObjectSampleIsDisabledOnUnsupportedVersion() throws Exception {
    ConfigProvider configProvider = ConfigProvider.createDefault();
    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!((isJavaVersion(11) && isJavaVersionAtLeast(11, 0, 12))
          || (isJavaVersion(15) && isJavaVersionAtLeast(15, 0, 4))
          || (isJavaVersion(16) && isJavaVersionAtLeast(16, 0, 2))
          || (isJavaVersion(17) && isJavaVersionAtLeast(17, 0, 3))
          || isJavaVersionAtLeast(18))) {
        assertEquals(
            false,
            Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
      }
    }
  }

  @Test
  public void testOldObjectSampleIsStillOverriddenOnUnsupportedVersion() throws Exception {
    Properties props = new Properties();
    props.put(
        ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE, JfpUtilsTest.OVERRIDES_OLD_OBJECT_SAMPLE);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!((isJavaVersion(11) && isJavaVersionAtLeast(11, 0, 12))
          || (isJavaVersion(15) && isJavaVersionAtLeast(15, 0, 4))
          || (isJavaVersion(16) && isJavaVersionAtLeast(16, 0, 2))
          || (isJavaVersion(17) && isJavaVersionAtLeast(17, 0, 3))
          || isJavaVersionAtLeast(18))) {
        assertEquals(
            true, Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
      }
    }
  }

  @Test
  public void testOldObjectSampleIsStillOverriddenThroughConfig() throws Exception {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_HEAP_ENABLED, true);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData) controller.createRecording(TEST_NAME).stop()).getRecording()) {
      assertEquals(
          Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")), true);
    }
  }

  @Test
  public void testObjectAllocationIsDisabledOnUnsupportedVersion() throws Exception {
    ConfigProvider configProvider = ConfigProvider.createDefault();

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!(isJavaVersionAtLeast(16))) {
        assertEquals(
            false,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationInNewTLAB#enabled")));
        assertEquals(
            false,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationOutsideTLAB#enabled")));
      }
    }
  }

  @Test
  public void testObjectAllocationIsStillOverriddenOnUnsupportedVersion() throws Exception {
    Properties props = new Properties();
    props.put(
        ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE, JfpUtilsTest.OVERRIDES_OBJECT_ALLOCATION);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!(isJavaVersionAtLeast(16))) {
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
  public void testObjectAllocationIsStillOverriddenThroughConfig() throws Exception {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_ALLOCATION_ENABLED, true);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    try (final Recording recording =
        ((OpenJdkRecordingData) controller.createRecording(TEST_NAME).stop()).getRecording()) {
      assertEquals(
          Boolean.parseBoolean(
              recording.getSettings().get("jdk.ObjectAllocationInNewTLAB#enabled")),
          true);
      assertEquals(
          Boolean.parseBoolean(
              recording.getSettings().get("jdk.ObjectAllocationOutsideTLAB#enabled")),
          true);
    }
  }

  @Test
  public void testNativeMethodSampleIsDisabledOnUnsupportedVersion() throws Exception {
    ConfigProvider configProvider = ConfigProvider.createDefault();

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!((isJavaVersion(8) && isJavaVersionAtLeast(8, 0, 302)) || isJavaVersionAtLeast(11))) {
        assertEquals(
            false,
            Boolean.parseBoolean(recording.getSettings().get("jdk.NativeMethodSample#enabled")));
      }
    }
  }

  @Test
  public void testNativeMethodSampleIsStillOverriddenOnUnsupportedVersion() throws Exception {
    Properties props = new Properties();
    props.put(
        ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE,
        JfpUtilsTest.OVERRIDES_NATIVE_METHOD_SAMPLE);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJdkController controller = new OpenJdkController(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!((isJavaVersion(8) && isJavaVersionAtLeast(8, 0, 302)) || isJavaVersionAtLeast(11))) {
        assertEquals(
            true,
            Boolean.parseBoolean(recording.getSettings().get("jdk.NativeMethodSample#enabled")));
      }
    }
  }
}
