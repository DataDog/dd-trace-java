package com.datadog.profiling.controller.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class JfpUtilsTest {
  private static final String CONFIG_ENTRY = "jdk.ThreadAllocationStatistics#enabled";
  private static final String CONFIG_OVERRIDE_ENTRY = "test.continuous.override#value";

  public static final String OVERRIDES =
      JfpUtilsTest.class.getClassLoader().getResource("overrides.jfp").getFile();
  public static final String OVERRIDES_OLD_OBJECT_SAMPLE =
      JfpUtilsTest.class.getClassLoader().getResource("overrides-oldobjectsample.jfp").getFile();

  @Test
  public void testLoadingInvalidOverride() throws IOException {
    final String INVALID_OVERRIDE = "really_non_existent_file.jfp";

    assertThrows(
        IOException.class,
        () -> JfpUtils.readNamedJfpResource(JfpUtils.DEFAULT_JFP, INVALID_OVERRIDE));
  }

  @Test
  public void testLoadingContinuousConfig() throws IOException {
    final Map<String, String> config = JfpUtils.readNamedJfpResource(JfpUtils.DEFAULT_JFP, null);
    assertEquals("true", config.get(CONFIG_ENTRY));
    assertNull(config.get(CONFIG_OVERRIDE_ENTRY));
  }

  @Test
  public void testLoadingContinuousConfigWithOverride() throws IOException {
    final Map<String, String> config =
        JfpUtils.readNamedJfpResource(JfpUtils.DEFAULT_JFP, OVERRIDES);
    assertEquals("true", config.get(CONFIG_ENTRY));
    assertEquals("200", config.get(CONFIG_OVERRIDE_ENTRY));
  }

  @ParameterizedTest
  @ValueSource(strings = {"minimal", "minimal.jfp"})
  public void testLoadingConfigMinimal(String override) throws IOException {
    Map<String, String> config = JfpUtils.readNamedJfpResource(JfpUtils.DEFAULT_JFP, override);
    assertEquals("500 ms", config.get("jdk.ThreadSleep#threshold"));
    assertEquals("false", config.get("jdk.OldObjectSample#enabled"));
    assertEquals("false", config.get("jdk.ObjectAllocationInNewTLAB#enabled"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"comprehensive", "comprehensive.jfp"})
  public void testLoadingConfigComprehensive(String override) throws IOException {
    Map<String, String> config = JfpUtils.readNamedJfpResource(JfpUtils.DEFAULT_JFP, override);
    assertEquals("10 ms", config.get("jdk.ThreadSleep#threshold"));
    assertEquals("true", config.get("jdk.OldObjectSample#enabled"));
    assertEquals("true", config.get("jdk.ObjectAllocationInNewTLAB#enabled"));
  }
}
