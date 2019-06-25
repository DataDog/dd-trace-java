package com.datadog.profiling.controller.openjdk;

import static com.datadog.profiling.controller.openjdk.JfpUtilsTest.CONTINUOUS_OVERRIDES;
import static com.datadog.profiling.controller.openjdk.JfpUtilsTest.PERIODIC_OVERRIDES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.datadog.profiling.controller.ConfigurationException;
import datadog.trace.api.Config;
import java.io.IOException;
import java.time.Duration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";
  private static final int MAX_SIZE = 123;
  private static final int MAX_AGE = 124;

  @Mock private Config config;
  private OpenJdkController controller;

  @BeforeEach
  public void setup() throws ConfigurationException, ClassNotFoundException {
    when(config.getProfilingPeriodicConfigOverridePath()).thenReturn(PERIODIC_OVERRIDES);
    when(config.getProfilingContinuousConfigOverridePath()).thenReturn(CONTINUOUS_OVERRIDES);
    when(config.getProfilingRecordingMaxSize()).thenReturn(MAX_SIZE);
    when(config.getProfilingRecordingMaxAge()).thenReturn(MAX_AGE);
    controller = new OpenJdkController(config);
  }

  @Test
  public void testCreatePeriodicRecording() throws IOException {
    final Recording recording = controller.createPeriodicRecording(TEST_NAME).stop().getRecording();
    assertEquals(TEST_NAME, recording.getName());
    assertEquals(
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP_PERIODIC, PERIODIC_OVERRIDES),
        recording.getSettings());
    assertEquals(MAX_SIZE, recording.getMaxSize());
    assertEquals(Duration.ofSeconds(MAX_AGE), recording.getMaxAge());
    recording.close();
  }

  @Test
  public void testCreateContinuousRecording() throws IOException {
    final Recording recording =
        controller.createContinuousRecording(TEST_NAME).stop().getRecording();
    assertEquals(TEST_NAME, recording.getName());
    assertEquals(
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP_CONTINUOUS, CONTINUOUS_OVERRIDES),
        recording.getSettings());
    assertEquals(MAX_SIZE, recording.getMaxSize());
    assertEquals(Duration.ofSeconds(MAX_AGE), recording.getMaxAge());
    recording.close();
  }
}
