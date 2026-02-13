package com.datadog.profiling.scrubber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultScrubDefinitionTest {

  /**
   * Gets the default scrub fields map via reflection for testing. This avoids needing to mock
   * ConfigProvider which is final.
   */
  @SuppressWarnings("unchecked")
  private Map<String, JfrScrubber.ScrubField> getDefaultScrubFields() throws Exception {
    Field field = DefaultScrubDefinition.class.getDeclaredField("DEFAULT_SCRUB_FIELDS");
    field.setAccessible(true);
    return (Map<String, JfrScrubber.ScrubField>) field.get(null);
  }

  @Test
  void defaultScrubFieldsContainsAllExpectedEventTypes() throws Exception {
    Map<String, JfrScrubber.ScrubField> fields = getDefaultScrubFields();

    // Verify all 4 default event types are present
    assertEquals(4, fields.size(), "Should have exactly 4 default event types");
    assertTrue(
        fields.containsKey("jdk.InitialSystemProperty"),
        "Should contain jdk.InitialSystemProperty");
    assertTrue(fields.containsKey("jdk.JVMInformation"), "Should contain jdk.JVMInformation");
    assertTrue(
        fields.containsKey("jdk.InitialEnvironmentVariable"),
        "Should contain jdk.InitialEnvironmentVariable");
    assertTrue(fields.containsKey("jdk.SystemProcess"), "Should contain jdk.SystemProcess");
  }

  @Test
  void defaultScrubFieldsHaveCorrectFieldNames() throws Exception {
    Map<String, JfrScrubber.ScrubField> fields = getDefaultScrubFields();

    // Verify InitialSystemProperty scrubs "value" field
    JfrScrubber.ScrubField systemPropertyField = fields.get("jdk.InitialSystemProperty");
    assertNotNull(systemPropertyField);
    assertEquals("value", systemPropertyField.scrubFieldName);
    assertNull(
        systemPropertyField.guardFieldName, "InitialSystemProperty should have no guard field");

    // Verify JVMInformation scrubs "jvmArguments" field
    JfrScrubber.ScrubField jvmInfoField = fields.get("jdk.JVMInformation");
    assertNotNull(jvmInfoField);
    assertEquals("jvmArguments", jvmInfoField.scrubFieldName);
    assertNull(jvmInfoField.guardFieldName, "JVMInformation should have no guard field");

    // Verify InitialEnvironmentVariable scrubs "value" field
    JfrScrubber.ScrubField envVarField = fields.get("jdk.InitialEnvironmentVariable");
    assertNotNull(envVarField);
    assertEquals("value", envVarField.scrubFieldName);
    assertNull(envVarField.guardFieldName, "InitialEnvironmentVariable should have no guard field");

    // Verify SystemProcess scrubs "commandLine" field
    JfrScrubber.ScrubField systemProcessField = fields.get("jdk.SystemProcess");
    assertNotNull(systemProcessField);
    assertEquals("commandLine", systemProcessField.scrubFieldName);
    assertNull(systemProcessField.guardFieldName, "SystemProcess should have no guard field");
  }

  @Test
  void guardFunctionsAlwaysReturnTrue() throws Exception {
    Map<String, JfrScrubber.ScrubField> fields = getDefaultScrubFields();

    // All default scrub fields should have guards that always return true
    for (JfrScrubber.ScrubField field : fields.values()) {
      assertNotNull(field.guard, "Guard should not be null");
      assertTrue(field.guard.apply("anyKey", "anyValue"), "Guard should return true");
      assertTrue(field.guard.apply(null, null), "Guard should return true for nulls");
      assertTrue(field.guard.apply("", ""), "Guard should return true for empty strings");
    }
  }

  @Test
  void defaultScrubFieldsMapIsUnmodifiable() throws Exception {
    Map<String, JfrScrubber.ScrubField> fields = getDefaultScrubFields();

    // Verify the map is unmodifiable
    try {
      fields.put("test", null);
      assertFalse(true, "Should have thrown UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      // Expected
    }
  }

  @Test
  void nonDefaultEventTypesNotInMap() throws Exception {
    Map<String, JfrScrubber.ScrubField> fields = getDefaultScrubFields();

    assertNull(fields.get("jdk.UnknownEvent"), "Unknown events should not be in map");
    assertNull(fields.get("custom.Event"), "Custom events should not be in map");
    assertNull(fields.get(""), "Empty string should not be in map");
  }

  @Test
  void scrubFieldToStringFormat() throws Exception {
    Map<String, JfrScrubber.ScrubField> fields = getDefaultScrubFields();
    JfrScrubber.ScrubField field = fields.get("jdk.InitialSystemProperty");

    String toString = field.toString();
    assertTrue(toString.contains("scrubFieldName"), "toString should contain scrubFieldName");
    assertTrue(toString.contains("value"), "toString should contain field name");
    assertTrue(toString.contains("guardFieldName"), "toString should contain guardFieldName");
  }
}
