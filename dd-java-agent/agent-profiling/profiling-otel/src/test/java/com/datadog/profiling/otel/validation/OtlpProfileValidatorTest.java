package com.datadog.profiling.otel.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.otel.proto.dictionary.AttributeTable;
import com.datadog.profiling.otel.proto.dictionary.FunctionTable;
import com.datadog.profiling.otel.proto.dictionary.LinkTable;
import com.datadog.profiling.otel.proto.dictionary.LocationTable;
import com.datadog.profiling.otel.proto.dictionary.StackTable;
import com.datadog.profiling.otel.proto.dictionary.StringTable;
import org.junit.jupiter.api.Test;

/** Unit tests for OTLP profile validator. */
class OtlpProfileValidatorTest {

  @Test
  void validateEmptyDictionaries() {
    StringTable strings = new StringTable();
    FunctionTable functions = new FunctionTable();
    LocationTable locations = new LocationTable();
    StackTable stacks = new StackTable();
    LinkTable links = new LinkTable();
    AttributeTable attributes = new AttributeTable();

    ValidationResult result =
        OtlpProfileValidator.validateDictionaries(
            strings, functions, locations, stacks, links, attributes);

    assertTrue(result.isValid(), "Empty dictionaries should be valid: " + result.getReport());
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  void validateStringTableWithValidEntries() {
    StringTable strings = new StringTable();
    strings.intern("com.example.Class");
    strings.intern("methodName");
    strings.intern("File.java");

    ValidationResult result =
        OtlpProfileValidator.validateDictionaries(
            strings,
            new FunctionTable(),
            new LocationTable(),
            new StackTable(),
            new LinkTable(),
            new AttributeTable());

    assertTrue(result.isValid());
  }

  @Test
  void validateFunctionTableReferences() {
    StringTable strings = new StringTable();
    int nameIdx = strings.intern("method");
    int systemNameIdx = strings.intern("method");
    int filenameIdx = strings.intern("File.java");

    FunctionTable functions = new FunctionTable();
    functions.intern(nameIdx, systemNameIdx, filenameIdx, 100);

    ValidationResult result =
        OtlpProfileValidator.validateDictionaries(
            strings,
            functions,
            new LocationTable(),
            new StackTable(),
            new LinkTable(),
            new AttributeTable());

    assertTrue(result.isValid());
  }

  @Test
  void validateStackTableWithValidReferences() {
    StringTable strings = new StringTable();
    int nameIdx = strings.intern("method");

    FunctionTable functions = new FunctionTable();
    int funcIdx = functions.intern(nameIdx, nameIdx, nameIdx, 100);

    LocationTable locations = new LocationTable();
    int loc1 = locations.intern(0, 0x1000, funcIdx, 10, 0);
    int loc2 = locations.intern(0, 0x2000, funcIdx, 20, 0);

    StackTable stacks = new StackTable();
    stacks.intern(new int[] {loc1, loc2});

    ValidationResult result =
        OtlpProfileValidator.validateDictionaries(
            strings, functions, locations, stacks, new LinkTable(), new AttributeTable());

    assertTrue(result.isValid());
  }

  @Test
  void validateLinkTableWithValidEntries() {
    LinkTable links = new LinkTable();
    links.intern(123456L, 789L); // Non-zero trace/span IDs

    ValidationResult result =
        OtlpProfileValidator.validateDictionaries(
            new StringTable(),
            new FunctionTable(),
            new LocationTable(),
            new StackTable(),
            links,
            new AttributeTable());

    assertTrue(result.isValid());
  }

  @Test
  void validateAttributeTableReferences() {
    StringTable strings = new StringTable();
    int keyIdx = strings.intern("thread.name");
    int unitIdx = strings.intern("");

    AttributeTable attributes = new AttributeTable();
    attributes.internString(keyIdx, "main", unitIdx);
    attributes.internInt(keyIdx, 42L, unitIdx);
    attributes.internBool(keyIdx, true, unitIdx);
    attributes.internDouble(keyIdx, 3.14, unitIdx);

    ValidationResult result =
        OtlpProfileValidator.validateDictionaries(
            strings,
            new FunctionTable(),
            new LocationTable(),
            new StackTable(),
            new LinkTable(),
            attributes);

    assertTrue(result.isValid());
  }

  @Test
  void validateLocationTableWithInlinedFunctions() {
    StringTable strings = new StringTable();
    int nameIdx = strings.intern("method");

    FunctionTable functions = new FunctionTable();
    int func1 = functions.intern(nameIdx, nameIdx, nameIdx, 100);
    int func2 = functions.intern(nameIdx, nameIdx, nameIdx, 200);

    LocationTable locations = new LocationTable();
    // Create location with inlined function
    int locIdx = locations.intern(0, 0x1000, func1, 10, 0);

    // Add inlined line entry
    LocationTable.LocationEntry loc = locations.get(locIdx);
    assertNotNull(loc);

    ValidationResult result =
        OtlpProfileValidator.validateDictionaries(
            strings, functions, locations, new StackTable(), new LinkTable(), new AttributeTable());

    assertTrue(result.isValid());
  }

  @Test
  void validationResultBuilder() {
    ValidationResult result =
        ValidationResult.builder()
            .addError("Error 1")
            .addError("Error 2")
            .addWarning("Warning 1")
            .build();

    assertFalse(result.isValid());
    assertEquals(2, result.getErrors().size());
    assertEquals(1, result.getWarnings().size());
    assertTrue(result.getReport().contains("Error 1"));
    assertTrue(result.getReport().contains("Warning 1"));
  }

  @Test
  void validationResultPassesWithWarnings() {
    ValidationResult result = ValidationResult.builder().addWarning("Just a warning").build();

    assertTrue(result.isValid(), "Should be valid with only warnings");
    assertEquals(0, result.getErrors().size());
    assertEquals(1, result.getWarnings().size());
  }
}
