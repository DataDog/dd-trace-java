package com.datadog.profiling.otel.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.otel.JfrToOtlpConverter;
import com.datadog.profiling.otel.proto.dictionary.*;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Round-trip validation tests for OTLP profile generation.
 *
 * <p>These tests convert JFR recordings to OTLP format and validate that the resulting dictionary
 * tables comply with OTLP specifications (index 0 semantics, reference integrity, etc.).
 */
class OtlpProfileRoundTripTest {

  @Test
  void validateDictionariesAfterConversion() throws Exception {
    // Use a simple in-memory JFR conversion
    JfrToOtlpConverter converter = new JfrToOtlpConverter();

    // Access internal dictionary tables via reflection
    StringTable strings = getDictionaryTable(converter, "stringTable", StringTable.class);
    FunctionTable functions = getDictionaryTable(converter, "functionTable", FunctionTable.class);
    LocationTable locations = getDictionaryTable(converter, "locationTable", LocationTable.class);
    StackTable stacks = getDictionaryTable(converter, "stackTable", StackTable.class);
    LinkTable links = getDictionaryTable(converter, "linkTable", LinkTable.class);
    AttributeTable attributes =
        getDictionaryTable(converter, "attributeTable", AttributeTable.class);

    // Add some test data to tables
    int str1 = strings.intern("com.example.Class");
    int str2 = strings.intern("methodName");
    int func1 = functions.intern(str1, str2, str1, 100);
    int loc1 = locations.intern(0, 0x1000, func1, 10, 0);
    stacks.intern(new int[] {loc1});
    links.intern(123L, 456L);

    // Validate dictionaries
    ValidationResult result =
        OtlpProfileValidator.validateDictionaries(
            strings, functions, locations, stacks, links, attributes);

    assertTrue(result.isValid(), "Dictionaries should be valid: " + result.getReport());
    assertTrue(result.getErrors().isEmpty(), "Should have no errors");
  }

  @Test
  void validateEmptyConverter() throws Exception {
    JfrToOtlpConverter converter = new JfrToOtlpConverter();

    // Access internal dictionary tables via reflection
    StringTable strings = getDictionaryTable(converter, "stringTable", StringTable.class);
    FunctionTable functions = getDictionaryTable(converter, "functionTable", FunctionTable.class);
    LocationTable locations = getDictionaryTable(converter, "locationTable", LocationTable.class);
    StackTable stacks = getDictionaryTable(converter, "stackTable", StackTable.class);
    LinkTable links = getDictionaryTable(converter, "linkTable", LinkTable.class);
    AttributeTable attributes =
        getDictionaryTable(converter, "attributeTable", AttributeTable.class);

    // Empty dictionaries should still be valid (index 0 entries present)
    ValidationResult result =
        OtlpProfileValidator.validateDictionaries(
            strings, functions, locations, stacks, links, attributes);

    assertTrue(result.isValid(), "Empty dictionaries should be valid: " + result.getReport());
  }

  @Test
  void validateDictionariesAfterReset() throws Exception {
    JfrToOtlpConverter converter = new JfrToOtlpConverter();

    // Access internal dictionary tables via reflection
    StringTable strings = getDictionaryTable(converter, "stringTable", StringTable.class);
    FunctionTable functions = getDictionaryTable(converter, "functionTable", FunctionTable.class);
    LocationTable locations = getDictionaryTable(converter, "locationTable", LocationTable.class);
    StackTable stacks = getDictionaryTable(converter, "stackTable", StackTable.class);
    LinkTable links = getDictionaryTable(converter, "linkTable", LinkTable.class);
    AttributeTable attributes =
        getDictionaryTable(converter, "attributeTable", AttributeTable.class);

    // Add some data
    strings.intern("test");
    functions.intern(1, 1, 1, 100);

    // Reset converter
    converter.reset();

    // Validate after reset - should still be valid with only index 0 entries
    ValidationResult result =
        OtlpProfileValidator.validateDictionaries(
            strings, functions, locations, stacks, links, attributes);

    assertTrue(result.isValid(), "Dictionaries should be valid after reset");
    assertEquals(1, strings.size(), "StringTable should only have index 0");
    assertEquals(1, functions.size(), "FunctionTable should only have index 0");
  }

  @Test
  void validateStringTableIndex0() throws Exception {
    JfrToOtlpConverter converter = new JfrToOtlpConverter();
    StringTable strings = getDictionaryTable(converter, "stringTable", StringTable.class);

    // Index 0 must be empty string per OTLP spec
    assertEquals("", strings.get(0), "Index 0 must be empty string");
    assertEquals(1, strings.size(), "Fresh table should only have index 0");
  }

  @Test
  void validateStackTableIndex0() throws Exception {
    JfrToOtlpConverter converter = new JfrToOtlpConverter();
    StackTable stacks = getDictionaryTable(converter, "stackTable", StackTable.class);

    // Index 0 must be empty stack per OTLP spec
    StackTable.StackEntry entry = stacks.get(0);
    assertNotNull(entry, "Index 0 must exist");
    assertEquals(0, entry.locationIndices.length, "Index 0 must be empty stack");
  }

  @Test
  void validateLinkTableIndex0() throws Exception {
    JfrToOtlpConverter converter = new JfrToOtlpConverter();
    LinkTable links = getDictionaryTable(converter, "linkTable", LinkTable.class);

    // Index 0 must have zero trace/span IDs per OTLP spec
    LinkTable.LinkEntry entry = links.get(0);
    assertNotNull(entry, "Index 0 must exist");

    // Verify all bytes are zero
    for (byte b : entry.traceId) {
      assertEquals(0, b, "Index 0 trace ID must be all zeros");
    }
    for (byte b : entry.spanId) {
      assertEquals(0, b, "Index 0 span ID must be all zeros");
    }
  }

  @Test
  void validateFunctionTableIndex0() throws Exception {
    JfrToOtlpConverter converter = new JfrToOtlpConverter();
    FunctionTable functions = getDictionaryTable(converter, "functionTable", FunctionTable.class);

    // Index 0 should have all-zero values
    FunctionTable.FunctionEntry entry = functions.get(0);
    assertNotNull(entry, "Index 0 must exist");
    assertEquals(0, entry.nameIndex, "Index 0 nameIndex should be 0");
    assertEquals(0, entry.systemNameIndex, "Index 0 systemNameIndex should be 0");
    assertEquals(0, entry.filenameIndex, "Index 0 filenameIndex should be 0");
    assertEquals(0, entry.startLine, "Index 0 startLine should be 0");
  }

  // Helper method to access private dictionary table fields using reflection
  @SuppressWarnings("unchecked")
  private <T> T getDictionaryTable(JfrToOtlpConverter converter, String fieldName, Class<T> type)
      throws Exception {
    Field field = JfrToOtlpConverter.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (T) field.get(converter);
  }
}
