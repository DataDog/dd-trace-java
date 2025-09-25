package datadog.trace.config.inversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.test.util.ControllableEnvironmentVariables;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ConfigHelperTest {
  // Test environment variables
  private static final String DD_VAR = "DD_TEST_CONFIG";
  private static final String DD_VAR_VAL = "test_dd_var";
  private static final String OTEL_VAR = "OTEL_TEST_CONFIG";
  private static final String OTEL_VAR_VAL = "test_otel_var";
  private static final String REGULAR_VAR = "REGULAR_TEST_CONFIG";
  private static final String REGULAR_VAR_VAL = "test_regular_var";

  private static final String ALIAS_DD_VAR = "DD_TEST_CONFIG_ALIAS";
  private static final String ALIAS_DD_VAL = "test_alias_val";
  private static final String NON_DD_ALIAS_VAR = "TEST_CONFIG_ALIAS";
  private static final String NON_DD_ALIAS_VAL = "test_alias_val_non_dd";

  private static final String NEW_ALIAS_TARGET = "DD_NEW_ALIAS_TARGET";
  private static final String NEW_ALIAS_KEY_1 = "DD_NEW_ALIAS_KEY_1";
  private static final String NEW_ALIAS_KEY_2 = "DD_NEW_ALIAS_KEY_2";

  private static ControllableEnvironmentVariables env;

  private static ConfigHelper.StrictnessPolicy strictness;
  private static TestSupportedConfigurationSource testSource;

  @BeforeAll
  static void setUp() {
    env = ControllableEnvironmentVariables.setup();

    // Set up test configurations using SupportedConfigurationSource
    Set<String> testSupported = new HashSet<>(Arrays.asList(DD_VAR, OTEL_VAR, REGULAR_VAR));

    Map<String, List<String>> testAliases = new HashMap<>();
    testAliases.put(DD_VAR, Arrays.asList(ALIAS_DD_VAR, NON_DD_ALIAS_VAR));
    testAliases.put(NEW_ALIAS_TARGET, Arrays.asList(NEW_ALIAS_KEY_1));

    Map<String, String> testAliasMapping = new HashMap<>();
    testAliasMapping.put(ALIAS_DD_VAR, DD_VAR);
    testAliasMapping.put(NON_DD_ALIAS_VAR, DD_VAR);
    testAliasMapping.put(NEW_ALIAS_KEY_2, NEW_ALIAS_TARGET);

    // Create and set test configuration source
    testSource =
        new TestSupportedConfigurationSource(
            testSupported, testAliases, testAliasMapping, new HashMap<>());
    ConfigHelper.get().setConfigurationSource(testSource);
    strictness = ConfigHelper.get().configInversionStrictFlag();
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.STRICT);
  }

  @AfterAll
  static void tearDown() {
    ConfigHelper.get().resetToDefaults();
    ConfigHelper.get().setConfigInversionStrict(strictness);
  }

  @AfterEach
  void reset() {
    ConfigHelper.get().resetCache();
    env.clear();
  }

  @Test
  void testBasicConfigHelper() {
    env.set(DD_VAR, DD_VAR_VAL);
    env.set(OTEL_VAR, OTEL_VAR_VAL);
    env.set(REGULAR_VAR, REGULAR_VAR_VAL);

    assertEquals(DD_VAR_VAL, ConfigHelper.get().getEnvironmentVariable(DD_VAR));
    assertEquals(OTEL_VAR_VAL, ConfigHelper.get().getEnvironmentVariable(OTEL_VAR));
    assertEquals(REGULAR_VAR_VAL, ConfigHelper.get().getEnvironmentVariable(REGULAR_VAR));

    Map<String, String> result = ConfigHelper.get().getEnvironmentVariables();
    assertEquals(DD_VAR_VAL, result.get(DD_VAR));
    assertEquals(OTEL_VAR_VAL, result.get(OTEL_VAR));
    assertEquals(REGULAR_VAR_VAL, result.get(REGULAR_VAR));
  }

  @Test
  void testAliasSupport() {
    env.set(ALIAS_DD_VAR, ALIAS_DD_VAL);

    assertEquals(ALIAS_DD_VAL, ConfigHelper.get().getEnvironmentVariable(DD_VAR));
    Map<String, String> result = ConfigHelper.get().getEnvironmentVariables();
    assertEquals(ALIAS_DD_VAL, result.get(DD_VAR));
    assertFalse(result.containsKey(ALIAS_DD_VAR));
  }

  @Test
  void testMainConfigPrecedence() {
    // When both main variable and alias are set, main should take precedence
    env.set(DD_VAR, DD_VAR_VAL);
    env.set(ALIAS_DD_VAR, ALIAS_DD_VAL);

    assertEquals(DD_VAR_VAL, ConfigHelper.get().getEnvironmentVariable(DD_VAR));
    Map<String, String> result = ConfigHelper.get().getEnvironmentVariables();
    assertEquals(DD_VAR_VAL, result.get(DD_VAR));
    assertFalse(result.containsKey(ALIAS_DD_VAR));
  }

  @Test
  void testNonDDAliases() {
    env.set(NON_DD_ALIAS_VAR, NON_DD_ALIAS_VAL);

    assertEquals(NON_DD_ALIAS_VAL, ConfigHelper.get().getEnvironmentVariable(DD_VAR));
    Map<String, String> result = ConfigHelper.get().getEnvironmentVariables();
    assertEquals(NON_DD_ALIAS_VAL, result.get(DD_VAR));
    assertFalse(result.containsKey(NON_DD_ALIAS_VAR));
  }

  @Test
  void testAliasesWithoutPresentAliases() {
    Map<String, String> result = ConfigHelper.get().getEnvironmentVariables();
    assertFalse(result.containsKey(ALIAS_DD_VAR));
  }

  @Test
  void testAliasWithEmptyList() {
    Map<String, List<String>> aliasMap = new HashMap<>();
    aliasMap.put("EMPTY_ALIAS_CONFIG", new ArrayList<>());

    ConfigHelper.get()
        .setConfigurationSource(
            new TestSupportedConfigurationSource(
                new HashSet<>(), aliasMap, new HashMap<>(), new HashMap<>()));

    assertNull(ConfigHelper.get().getEnvironmentVariable("EMPTY_ALIAS_CONFIG"));

    // Cleanup
    ConfigHelper.get().setConfigurationSource(testSource);
  }

  @Test
  void testAliasSkippedWhenBaseAlreadyPresent() {
    env.set(DD_VAR, DD_VAR_VAL);
    env.set(NON_DD_ALIAS_VAR, NON_DD_ALIAS_VAL);

    Map<String, String> result = ConfigHelper.get().getEnvironmentVariables();
    assertEquals(DD_VAR_VAL, result.get(DD_VAR));
    assertFalse(result.containsKey(NON_DD_ALIAS_VAR));
  }

  @Test
  void testInconsistentAliasesAndAliasMapping() {
    env.set(NEW_ALIAS_KEY_2, "some_value");

    Map<String, String> result = ConfigHelper.get().getEnvironmentVariables();

    assertFalse(result.containsKey(NEW_ALIAS_KEY_2));
    assertFalse(result.containsKey(NEW_ALIAS_TARGET));
  }

  @Test
  void testUnsupportedEnvWarningNotInTestMode() {
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST);

    env.set("DD_FAKE_VAR", "banana");

    // Should allow unsupported variable in TEST mode
    assertEquals("banana", ConfigHelper.get().getEnvironmentVariable("DD_FAKE_VAR"));

    // Cleanup
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.STRICT);
  }

  @Test
  void testCache() {
    env.set(DD_VAR, DD_VAR_VAL);

    Map<String, String> result = ConfigHelper.get().getEnvironmentVariables();
    assertEquals(DD_VAR_VAL, result.get(DD_VAR));

    // Ensure that the cached value is returned
    env.set(DD_VAR, ALIAS_DD_VAL);
    assertEquals(DD_VAR_VAL, result.get(DD_VAR));
    assertEquals(DD_VAR_VAL, ConfigHelper.get().getEnvironmentVariable(DD_VAR));
  }
}
