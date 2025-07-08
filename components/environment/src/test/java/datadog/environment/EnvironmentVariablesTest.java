package datadog.environment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EnvironmentVariablesTest {
  private static final String EXISTING_ENV_VAR = "JAVA_8_HOME";
  private static final String MISSING_ENV_VAR = "UNDEFINED_ENV_VAR";

  @Test
  void testGet() {
    assertNotNull(EnvironmentVariables.get(EXISTING_ENV_VAR));
    assertNull(EnvironmentVariables.get(MISSING_ENV_VAR));
    assertThrows(NullPointerException.class, () -> EnvironmentVariables.get(null));
  }

  @Test
  void testGetOrDefault() {
    assertNotNull(EnvironmentVariables.getOrDefault(EXISTING_ENV_VAR, null));

    assertEquals("", EnvironmentVariables.getOrDefault(MISSING_ENV_VAR, ""));
    assertNull(EnvironmentVariables.getOrDefault(MISSING_ENV_VAR, null));

    assertThrows(NullPointerException.class, () -> EnvironmentVariables.getOrDefault(null, ""));
  }
}
