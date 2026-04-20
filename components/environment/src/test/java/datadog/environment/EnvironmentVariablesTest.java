package datadog.environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentVariablesTest {
  private static final String EXISTING_ENV_VAR = "PATH";
  private static final String MISSING_ENV_VAR = "UNDEFINED_ENV_VAR";
  private static final String DEFAULT_VALUE = "DEFAULT";

  @Test
  void testGet() {
    // Existing environment variable
    assertNotNull(EnvironmentVariables.get(EXISTING_ENV_VAR));
    // Missing environment variable
    assertNull(EnvironmentVariables.get(MISSING_ENV_VAR));
    // Null values
    assertDoesNotThrow(() -> EnvironmentVariables.get(null));
    assertNull(EnvironmentVariables.get(null));
  }

  @Test
  void testGetOrDefault() {
    // Existing environment variable
    assertNotNull(EnvironmentVariables.getOrDefault(EXISTING_ENV_VAR, null));
    // Missing environment variable
    assertEquals(DEFAULT_VALUE, EnvironmentVariables.getOrDefault(MISSING_ENV_VAR, DEFAULT_VALUE));
    assertNull(EnvironmentVariables.getOrDefault(MISSING_ENV_VAR, null));
    // Null values
    assertDoesNotThrow(() -> EnvironmentVariables.getOrDefault(null, DEFAULT_VALUE));
    assertEquals(DEFAULT_VALUE, EnvironmentVariables.getOrDefault(null, DEFAULT_VALUE));
    assertDoesNotThrow(() -> EnvironmentVariables.getOrDefault(MISSING_ENV_VAR, null));
    assertNull(EnvironmentVariables.getOrDefault(MISSING_ENV_VAR, null));
  }

  @Test
  void testGetAll() {
    Map<String, String> all = EnvironmentVariables.getAll();
    assertNotNull(all);
    assertFalse(all.isEmpty());
    // Unmodifiable collection
    assertThrows(UnsupportedOperationException.class, all::clear);
  }
}
