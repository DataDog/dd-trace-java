package com.datadog.profiling.controller.openjdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class JfpUtilsTest {

  // Config entry that has different value in periodic and continuous configs
  private static final String TEST_CONFIG_ENTRY = "jdk.SafepointBegin#enabled";
  private static final String PERIODIC_CONFIG_OVERRIDE_ENTRY = "test.periodic.override#value";
  private static final String CONTINUOUS_CONFIG_OVERRIDE_ENTRY = "test.continuous.override#value";

  static final String PERIODIC_OVERRIDES =
      OpenJdkControllerTest.class.getClassLoader().getResource("periodic-overrides.jfp").getFile();
  static final String CONTINUOUS_OVERRIDES =
      OpenJdkControllerTest.class
          .getClassLoader()
          .getResource("continuous-overrides.jfp")
          .getFile();

  @Test
  public void testLoadingPeriodicConfig() throws IOException {
    final Map<String, String> config =
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP_PROFILE, null);
    assertEquals("true", config.get(TEST_CONFIG_ENTRY));
    assertNull(config.get(PERIODIC_CONFIG_OVERRIDE_ENTRY));
  }

  @Test
  public void testLoadingContinuousConfig() throws IOException {
    final Map<String, String> config =
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP_CONTINUOUS, null);
    assertEquals("false", config.get(TEST_CONFIG_ENTRY));
    assertNull(config.get(CONTINUOUS_CONFIG_OVERRIDE_ENTRY));
  }

  @Test
  public void testLoadingPeriodicConfigWithOverride() throws IOException {
    final Map<String, String> config =
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP_PROFILE, PERIODIC_OVERRIDES);
    assertEquals("periodic-test-value", config.get(TEST_CONFIG_ENTRY));
    assertEquals("100", config.get(PERIODIC_CONFIG_OVERRIDE_ENTRY));
  }

  @Test
  public void testLoadingContinuousConfigWithOverride() throws IOException {
    final Map<String, String> config =
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP_CONTINUOUS, CONTINUOUS_OVERRIDES);
    assertEquals("continuous-test-value", config.get(TEST_CONFIG_ENTRY));
    assertEquals("200", config.get(CONTINUOUS_CONFIG_OVERRIDE_ENTRY));
  }
}
