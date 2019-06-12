package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.ConfigurationException;
import datadog.trace.api.Config;
import java.io.IOException;
import jdk.jfr.Recording;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.datadog.profiling.controller.openjdk.JfpUtilsTest.CONTINUOUS_OVERRIDES;
import static com.datadog.profiling.controller.openjdk.JfpUtilsTest.PERIODIC_OVERRIDES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";

  @Mock private Config config;
  private OpenJdkController controller;

  @BeforeEach
  public void setup() throws ConfigurationException, ClassNotFoundException {
    when(config.getProfilingPeriodicConfigOverridePath()).thenReturn(PERIODIC_OVERRIDES);
    when(config.getProfilingContinuousConfigOverridePath()).thenReturn(CONTINUOUS_OVERRIDES);
    controller = new OpenJdkController(config);
  }

  @Test
  public void testCreateRecording() throws IOException {
    final Recording recording = controller.createRecording(TEST_NAME).stop().getRecording();
    assertEquals(TEST_NAME, recording.getName());
    assertEquals(
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP_PROFILE, PERIODIC_OVERRIDES),
        recording.getSettings());
  }

  @Test
  public void testCreateContinuousRecording() throws IOException {
    final Recording recording =
        controller.createContinuousRecording(TEST_NAME).stop().getRecording();
    assertEquals(TEST_NAME, recording.getName());
    assertEquals(
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP_CONTINUOUS, CONTINUOUS_OVERRIDES),
        recording.getSettings());
  }
}
