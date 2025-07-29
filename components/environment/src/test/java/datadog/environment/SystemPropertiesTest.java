package datadog.environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class SystemPropertiesTest {
  private static final String EXISTING_SYSTEM_PROPERTY = "java.home";
  private static final String MISSING_SYSTEM_PROPERTY = "undefined.system.property";
  private static final String DEFAULT_VALUE = "DEFAULT";

  @Test
  void testGet() {
    // Existing system properties
    assertNotNull(SystemProperties.get(EXISTING_SYSTEM_PROPERTY));
    // Missing system properties
    assertNull(SystemProperties.get(MISSING_SYSTEM_PROPERTY));
    // Null values
    assertDoesNotThrow(() -> SystemProperties.get(null));
    assertNull(SystemProperties.get(null));
  }

  @Test
  void testGetOrDefault() {
    // Existing system properties
    assertNotNull(SystemProperties.getOrDefault(EXISTING_SYSTEM_PROPERTY, null));
    // Missing system properties
    assertEquals(
        DEFAULT_VALUE, SystemProperties.getOrDefault(MISSING_SYSTEM_PROPERTY, DEFAULT_VALUE));
    assertNull(SystemProperties.getOrDefault(MISSING_SYSTEM_PROPERTY, null));
    // Null values
    assertDoesNotThrow(() -> SystemProperties.getOrDefault(null, DEFAULT_VALUE));
    assertEquals(DEFAULT_VALUE, SystemProperties.getOrDefault(null, DEFAULT_VALUE));
    assertDoesNotThrow(() -> SystemProperties.getOrDefault(MISSING_SYSTEM_PROPERTY, null));
    assertNull(SystemProperties.getOrDefault(MISSING_SYSTEM_PROPERTY, null));
  }

  @Test
  void testSet() {
    String testProperty = "test.set.property";
    String testValue = "test.set.value";
    assertNull(SystemProperties.get(testProperty));
    assertTrue(SystemProperties.set(testProperty, testValue));
    assertEquals(testValue, SystemProperties.get(testProperty));
    // Null values
    assertDoesNotThrow(() -> SystemProperties.set(testProperty, null));
    assertFalse(SystemProperties.set(testProperty, null));
    assertDoesNotThrow(() -> SystemProperties.set(null, testValue));
    assertFalse(SystemProperties.set(null, testValue));
  }

  @Test
  void testClear() {
    String testProperty = "test.clear.property";
    String testValue = "test.clear.value";
    assertNull(SystemProperties.get(testProperty));
    assertNull(SystemProperties.clear(testProperty));
    assumeTrue(SystemProperties.set(testProperty, testValue));
    assertEquals(testValue, SystemProperties.clear(testProperty));
    assertNull(SystemProperties.clear(testProperty));
    // Null values
    assertDoesNotThrow(() -> SystemProperties.clear(null));
    assertNull(SystemProperties.clear(null));
  }
}
