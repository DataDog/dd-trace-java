package datadog.environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class SystemPropertiesTest {
  private static final String EXISTING_SYSTEM_PROPERTY = "java.home";
  private static final String MISSING_SYSTEM_PROPERTY = "undefined.system.property";

  @Test
  void testGet() {
    assertNotNull(SystemProperties.get(EXISTING_SYSTEM_PROPERTY));
    assertNull(SystemProperties.get(MISSING_SYSTEM_PROPERTY));
    assertThrows(NullPointerException.class, () -> SystemProperties.get(null));
  }

  @Test
  void testGetOrDefault() {
    assertNotNull(SystemProperties.getOrDefault(EXISTING_SYSTEM_PROPERTY, null));

    assertEquals("", SystemProperties.getOrDefault(MISSING_SYSTEM_PROPERTY, ""));
    assertNull(SystemProperties.getOrDefault(MISSING_SYSTEM_PROPERTY, null));

    assertThrows(NullPointerException.class, () -> SystemProperties.getOrDefault(null, ""));
  }

  @Test
  void testSet() {
    String testProperty = "test.property";
    String testValue = "test-value";
    assumeTrue(SystemProperties.get(testProperty) == null);

    assertTrue(SystemProperties.set(testProperty, testValue));
    assertEquals(testValue, SystemProperties.get(testProperty));
  }
}
