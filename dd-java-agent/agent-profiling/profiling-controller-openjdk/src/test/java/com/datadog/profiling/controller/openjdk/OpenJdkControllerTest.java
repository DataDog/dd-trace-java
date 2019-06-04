package com.datadog.profiling.controller.openjdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.profiling.controller.ConfigurationException;
import java.io.IOException;
import jdk.jfr.Recording;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";

  private OpenJdkController controller;

  @Before
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
