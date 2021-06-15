package com.datadog.profiling.controller.openjdk;

import static datadog.trace.api.Platform.isJavaVersion;
import static datadog.trace.api.Platform.isJavaVersionAtLeast;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.datadog.profiling.controller.jfr.JfpUtils;
import com.datadog.profiling.controller.jfr.JfpUtilsTest;
import datadog.trace.api.Config;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";

  @Mock private Config config;

  @Test
  public void testCreateContinuousRecording() throws Exception {
    when(config.getProfilingTemplateOverrideFile()).thenReturn(JfpUtilsTest.OVERRIDES);
    OpenJdkController controller = new OpenJdkController(config);
    try (final Recording recording = controller.createRecording(TEST_NAME).stop().getRecording()) {
      assertEquals(TEST_NAME, recording.getName());
      assertEquals(
          JfpUtils.readNamedJfpResource(JfpUtils.DEFAULT_JFP, JfpUtilsTest.OVERRIDES),
          recording.getSettings());
      assertEquals(OpenJdkController.RECORDING_MAX_SIZE, recording.getMaxSize());
      assertEquals(OpenJdkController.RECORDING_MAX_AGE, recording.getMaxAge());
    }
  }

  @Test
  public void testOldObjectSampleIsDisabledOnUnsupportedVersion() throws Exception {
    when(config.getProfilingTemplateOverrideFile())
        .thenReturn(JfpUtilsTest.OVERRIDES_OLD_OBJECT_SAMPLE);
    OpenJdkController controller = new OpenJdkController(config);
    try (final Recording recording = controller.createRecording(TEST_NAME).stop().getRecording()) {
      if (!((isJavaVersion(11) && isJavaVersionAtLeast(11, 0, 12))
          || (isJavaVersion(15) && isJavaVersionAtLeast(15, 0, 4))
          || (isJavaVersion(16) && isJavaVersionAtLeast(16, 0, 2))
          || isJavaVersionAtLeast(17))) {
        assertEquals(
            Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")),
            false);
      }
    }
  }
}
