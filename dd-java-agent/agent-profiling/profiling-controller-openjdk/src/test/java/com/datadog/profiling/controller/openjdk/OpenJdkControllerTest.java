package com.datadog.profiling.controller.openjdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

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
  private static final String PERIODIC_OVERRIDES =
      OpenJdkControllerTest.class.getClassLoader().getResource("periodic-overrides.jfp").getFile();
  private static final String CONTINUOUS_OVERRIDES =
      OpenJdkControllerTest.class
          .getClassLoader()
          .getResource("continuous-overrides.jfp")
          .getFile();

  @Mock private Config config;
  private OpenJdkController controller;

  @BeforeEach
  public void setup() throws ConfigurationException {
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
