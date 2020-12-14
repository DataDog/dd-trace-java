package com.datadog.profiling.controller.openjdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class JfpUtilsTest {
  private static final String CONFIG_ENTRY = "jdk.ThreadAllocationStatistics#enabled";
  private static final String CONFIG_OVERRIDE_ENTRY = "test.continuous.override#value";

  static final String OVERRIDES =
      OpenJdkControllerTest.class.getClassLoader().getResource("overrides.jfp").getFile();

  @Test
  public void testLoadingInvalidOverride() throws IOException {
    final String INVALID_OVERRIDE = "really_non_existent_file.jfp";

    assertThrows(
        IOException.class,
        () -> JfpUtils.readNamedJfpResource(OpenJdkController.JFP, INVALID_OVERRIDE));
  }

  @Test
  public void testLoadingContinuousConfig() throws IOException {
    final Map<String, String> config = JfpUtils.readNamedJfpResource(OpenJdkController.JFP, null);
    assertEquals("true", config.get(CONFIG_ENTRY));
    assertNull(config.get(CONFIG_OVERRIDE_ENTRY));
  }

  @Test
  public void testLoadingContinuousConfigWithOverride() throws IOException {
    final Map<String, String> config =
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP, OVERRIDES);
    assertEquals("true", config.get(CONFIG_ENTRY));
    assertEquals("200", config.get(CONFIG_OVERRIDE_ENTRY));
  }

  @Test
  public void testLoadingConfigMinimal() throws IOException {
    Map<String, String> config =
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP, "minimal.jfp");
    assertEquals("500 ms", config.get("jdk.ThreadSleep#threshold"));
    assertEquals("false", config.get("jdk.OldObjectSample#enabled"));
    assertEquals("false", config.get("jdk.ObjectAllocationInNewTLAB#enabled"));
  }

  @Test
  public void testLoadingConfigComprehensive() throws IOException {
    Map<String, String> config =
        JfpUtils.readNamedJfpResource(OpenJdkController.JFP, "comprehensive.jfp");
    assertEquals("10 ms", config.get("jdk.ThreadSleep#threshold"));
    assertEquals("true", config.get("jdk.OldObjectSample#enabled"));
    assertEquals("true", config.get("jdk.ObjectAllocationInNewTLAB#enabled"));
  }
}
