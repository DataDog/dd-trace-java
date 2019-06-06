package com.datadog.profiling.controller.openjdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.profiling.controller.ConfigurationException;
import java.io.IOException;
import jdk.jfr.Recording;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";

  private OpenJdkController controller;

  @BeforeEach
  public void setup() throws ConfigurationException {
    controller = new OpenJdkController();
  }

  @Test
  public void testCreateRecording() throws IOException {
    final Recording recording = controller.createRecording(TEST_NAME).stop().getRecording();
    assertEquals(TEST_NAME, recording.getName());
    assertEquals(
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP_PROFILE), recording.getSettings());
  }

  @Test
  public void testCreateContinuousRecording() throws IOException {
    final Recording recording =
        controller.createContinuousRecording(TEST_NAME).stop().getRecording();
    assertEquals(TEST_NAME, recording.getName());
    assertEquals(
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP_CONTINUOUS), recording.getSettings());
  }
}
