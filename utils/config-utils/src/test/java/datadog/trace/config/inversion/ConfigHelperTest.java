package datadog.trace.config.inversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ConfigHelperTest {
  // Test environment variables
  private static final String TEST_DD_VAR = "DD_TEST_CONFIG";
  private static final String TEST_DD_VAR_VAL = "test_dd_var";
  private static final String TEST_OTEL_VAR = "OTEL_TEST_CONFIG";
  private static final String TEST_OTEL_VAR_VAL = "test_otel_var";
  private static final String TEST_REGULAR_VAR = "REGULAR_TEST_CONFIG";
  private static final String TEST_REGULAR_VAR_VAL = "test_regular_var";
  private static final String UNSUPPORTED_DD_VAR = "DD_UNSUPPORTED_CONFIG";

  private static final String ALIAS_DD_VAR = "DD_TEST_CONFIG_ALIAS";
  private static final String ALIAS_DD_VAL = "test_alias_val";
  private static final String NON_DD_ALIAS_VAR = "TEST_CONFIG_ALIAS";
  private static final String NON_DD_ALIAS_VAL = "test_alias_val_non_dd";

  private static final String NEW_ALIAS_TARGET = "DD_NEW_ALIAS_TARGET";
  private static final String NEW_ALIAS_KEY_1 = "DD_NEW_ALIAS_KEY_1";
  private static final String NEW_ALIAS_KEY_2 = "DD_NEW_ALIAS_KEY_2";

  private static ConfigInversionStrictStyle strictness;
  private static TestSupportedConfigurationSource testSource;

  @BeforeAll
  static void setUp() {
    // Set up test configurations using SupportedConfigurationSource
    //
    // ConfigInversionMetricCollectorProvider.register(ConfigInversionMetricCollectorImpl.getInstance());
    Set<String> testSupported =
        new HashSet<>(Arrays.asList(TEST_DD_VAR, TEST_OTEL_VAR, TEST_REGULAR_VAR));

    Map<String, List<String>> testAliases = new HashMap<>();
    testAliases.put(TEST_DD_VAR, Arrays.asList(ALIAS_DD_VAR, NON_DD_ALIAS_VAR));
    testAliases.put(NEW_ALIAS_TARGET, Arrays.asList(NEW_ALIAS_KEY_1));

    Map<String, String> testAliasMapping = new HashMap<>();
    testAliasMapping.put(ALIAS_DD_VAR, TEST_DD_VAR);
    testAliasMapping.put(NON_DD_ALIAS_VAR, TEST_DD_VAR);
    testAliasMapping.put(NEW_ALIAS_KEY_2, NEW_ALIAS_TARGET);

    // Create and set test configuration source
    testSource =
        new TestSupportedConfigurationSource(
            testSupported, testAliases, testAliasMapping, new HashMap<>());
    ConfigHelper.setConfigurationSource(testSource);
    strictness = ConfigHelper.configInversionStrictFlag();
    ConfigHelper.setConfigInversionStrict(ConfigInversionStrictStyle.STRICT);
  }

  @AfterAll
  static void tearDown() {
    ConfigHelper.resetToDefaults();
    ConfigHelper.setConfigInversionStrict(strictness);
  }

  @Test
  void testBasicConfigHelper() {
    setEnvVar(TEST_DD_VAR, TEST_DD_VAR_VAL);
    setEnvVar(TEST_OTEL_VAR, TEST_OTEL_VAR_VAL);
    setEnvVar(TEST_REGULAR_VAR, TEST_REGULAR_VAR_VAL);

    assertEquals(TEST_DD_VAR_VAL, ConfigHelper.getEnvironmentVariable(TEST_DD_VAR));
    assertEquals(TEST_OTEL_VAR_VAL, ConfigHelper.getEnvironmentVariable(TEST_OTEL_VAR));
    assertEquals(TEST_REGULAR_VAR_VAL, ConfigHelper.getEnvironmentVariable(TEST_REGULAR_VAR));

    Map<String, String> result = ConfigHelper.getEnvironmentVariables();
    assertEquals(TEST_DD_VAR_VAL, result.get(TEST_DD_VAR));
    assertEquals(TEST_OTEL_VAR_VAL, result.get(TEST_OTEL_VAR));
    assertEquals(TEST_REGULAR_VAR_VAL, result.get(TEST_REGULAR_VAR));

    // Cleanup
    setEnvVar(TEST_DD_VAR, null);
    setEnvVar(TEST_OTEL_VAR, null);
    setEnvVar(TEST_REGULAR_VAR, null);
  }

  @Test
  void testAliasSupport() {
    setEnvVar(ALIAS_DD_VAR, ALIAS_DD_VAL);

    assertEquals(ALIAS_DD_VAL, ConfigHelper.getEnvironmentVariable(TEST_DD_VAR));
    Map<String, String> result = ConfigHelper.getEnvironmentVariables();
    assertEquals(ALIAS_DD_VAL, result.get(TEST_DD_VAR));
    assertFalse(result.containsKey(ALIAS_DD_VAR));

    // Cleanup
    setEnvVar(ALIAS_DD_VAR, null);
  }

  @Test
  void testMainConfigPrecedence() {
    // When both main variable and alias are set, main should take precedence
    setEnvVar(TEST_DD_VAR, TEST_DD_VAR_VAL);
    setEnvVar(ALIAS_DD_VAR, ALIAS_DD_VAL);

    assertEquals(TEST_DD_VAR_VAL, ConfigHelper.getEnvironmentVariable(TEST_DD_VAR));
    Map<String, String> result = ConfigHelper.getEnvironmentVariables();
    assertEquals(TEST_DD_VAR_VAL, result.get(TEST_DD_VAR));
    assertFalse(result.containsKey(ALIAS_DD_VAR));

    // Cleanup
    setEnvVar(TEST_DD_VAR, null);
    setEnvVar(ALIAS_DD_VAR, null);
  }

  @Test
  void testNonDDAliases() {
    setEnvVar(NON_DD_ALIAS_VAR, NON_DD_ALIAS_VAL);

    assertEquals(NON_DD_ALIAS_VAL, ConfigHelper.getEnvironmentVariable(TEST_DD_VAR));
    Map<String, String> result = ConfigHelper.getEnvironmentVariables();
    assertEquals(NON_DD_ALIAS_VAL, result.get(TEST_DD_VAR));
    assertFalse(result.containsKey(NON_DD_ALIAS_VAR));

    // Cleanup
    setEnvVar(NON_DD_ALIAS_VAR, null);
  }

  @Test
  void testAliasesWithoutPresentAliases() {
    Map<String, String> result = ConfigHelper.getEnvironmentVariables();
    assertFalse(result.containsKey(ALIAS_DD_VAR));
  }

  @Test
  void testAliasWithEmptyList() {
    Map<String, List<String>> aliasMap = new HashMap<>();
    aliasMap.put("EMPTY_ALIAS_CONFIG", new ArrayList<>());

    ConfigHelper.setConfigurationSource(
        new TestSupportedConfigurationSource(
            new HashSet<>(), aliasMap, new HashMap<>(), new HashMap<>()));

    assertNull(ConfigHelper.getEnvironmentVariable("EMPTY_ALIAS_CONFIG"));

    // Cleanup
    ConfigHelper.setConfigurationSource(testSource);
  }

  @Test
  void testAliasSkippedWhenBaseAlreadyPresent() {
    setEnvVar(TEST_DD_VAR, TEST_DD_VAR_VAL);
    setEnvVar(NON_DD_ALIAS_VAR, NON_DD_ALIAS_VAL);

    Map<String, String> result = ConfigHelper.getEnvironmentVariables();
    assertEquals(TEST_DD_VAR_VAL, result.get(TEST_DD_VAR));
    assertFalse(result.containsKey(NON_DD_ALIAS_VAR));

    // Cleanup
    setEnvVar(TEST_DD_VAR, null);
    setEnvVar(NON_DD_ALIAS_VAR, null);
  }

  @Test
  void testInconsistentAliasesAndAliasMapping() {
    setEnvVar(NEW_ALIAS_KEY_2, "some_value");

    Map<String, String> result = ConfigHelper.getEnvironmentVariables();

    assertFalse(result.containsKey(NEW_ALIAS_KEY_2));
    assertFalse(result.containsKey(NEW_ALIAS_TARGET));

    // Cleanup
    setEnvVar(NEW_ALIAS_KEY_2, null);
  }

  // TODO: Update to verify telemetry when implemented
  @Test
  void testUnsupportedEnvWarningNotInTestMode() {
    ConfigHelper.setConfigInversionStrict(ConfigInversionStrictStyle.TEST);

    setEnvVar("DD_FAKE_VAR", "banana");

    // Should allow unsupported variable in TEST mode
    assertEquals("banana", ConfigHelper.getEnvironmentVariable("DD_FAKE_VAR"));

    // Cleanup
    setEnvVar("DD_FAKE_VAR", null);
    ConfigHelper.setConfigInversionStrict(ConfigInversionStrictStyle.STRICT);
  }

  // Copied from utils.TestHelper
  @SuppressWarnings("unchecked")
  private static void setEnvVar(String envName, String envValue) {
    try {
      Class<?> classOfMap = System.getenv().getClass();
      Field field = classOfMap.getDeclaredField("m");
      field.setAccessible(true);
      if (envValue == null) {
        ((Map<String, String>) field.get(System.getenv())).remove(envName);
      } else {
        ((Map<String, String>) field.get(System.getenv())).put(envName, envValue);
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }
}
