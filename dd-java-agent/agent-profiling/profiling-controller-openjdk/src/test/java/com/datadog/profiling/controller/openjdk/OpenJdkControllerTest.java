package com.datadog.profiling.controller.openjdk;

import static com.datadog.profiling.controller.openjdk.JfpUtilsTest.OVERRIDES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.datadog.profiling.controller.ConfigurationException;
import datadog.trace.api.Config;
import java.io.IOException;
import jdk.jfr.Recording;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";

  @Mock private Config config;

  @ParameterizedTest
  @EnumSource(JfpUtils.Level.class)
  public void testCreateContinuousRecording(JfpUtils.Level level)
      throws ConfigurationException, ClassNotFoundException, IOException {
    /*
     * Can not run the setup in @BeforeEach block because controller instance is configured by a test parameter
     * and @BeforeEach parameterization is not supported in JUnit 5
     */
    when(config.getProfilingTemplate()).thenReturn(level.name());
    when(config.getProfilingTemplateOverrideFile()).thenReturn(OVERRIDES);
    OpenJdkController controller = new OpenJdkController(config);
    // -- end setup

    final Recording recording = controller.createRecording(TEST_NAME).stop().getRecording();
    assertEquals(TEST_NAME, recording.getName());
    assertEquals(
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP, level, OVERRIDES),
        recording.getSettings());
    assertEquals(OpenJdkController.RECORDING_MAX_SIZE, recording.getMaxSize());
    assertEquals(OpenJdkController.RECORDING_MAX_AGE, recording.getMaxAge());
    recording.close();
  }
}
