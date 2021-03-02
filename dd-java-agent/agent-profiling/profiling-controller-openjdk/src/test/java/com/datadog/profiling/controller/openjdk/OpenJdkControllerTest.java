package com.datadog.profiling.controller.openjdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.datadog.profiler.controller.jfr.JfpUtils;
import com.datadog.profiler.controller.jfr.JfpUtilsTest;
import com.datadog.profiling.controller.ConfigurationException;
import datadog.trace.api.Config;
import java.io.IOException;
import jdk.jfr.Recording;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";

  @Mock private Config config;
  private OpenJdkController controller;

  @BeforeEach
  public void setup() throws ConfigurationException, ClassNotFoundException {
    when(config.getProfilingTemplateOverrideFile()).thenReturn(JfpUtilsTest.OVERRIDES);
    controller = new OpenJdkController(config);
  }

  @Test
  public void testCreateContinuousRecording() throws IOException {
    final Recording recording = controller.createRecording(TEST_NAME).stop().getRecording();
    assertEquals(TEST_NAME, recording.getName());
    assertEquals(
        JfpUtils.readNamedJfpResource(JfpUtils.DEFAULT_JFP, JfpUtilsTest.OVERRIDES),
        recording.getSettings());
    assertEquals(OpenJdkController.RECORDING_MAX_SIZE, recording.getMaxSize());
    assertEquals(OpenJdkController.RECORDING_MAX_AGE, recording.getMaxAge());
    recording.close();
  }
}
