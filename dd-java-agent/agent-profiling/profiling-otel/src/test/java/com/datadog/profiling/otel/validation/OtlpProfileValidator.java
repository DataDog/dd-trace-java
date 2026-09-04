package com.datadog.profiling.otel.validation;

import com.datadog.profiling.otel.proto.dictionary.AttributeTable;
import com.datadog.profiling.otel.proto.dictionary.FunctionTable;
import com.datadog.profiling.otel.proto.dictionary.LinkTable;
import com.datadog.profiling.otel.proto.dictionary.LocationTable;
import com.datadog.profiling.otel.proto.dictionary.StackTable;
import com.datadog.profiling.otel.proto.dictionary.StringTable;

/**
 * Validator for OTLP profile structures to ensure compliance with OpenTelemetry specifications.
 * Validates dictionary table constraints, sample consistency, and reference integrity.
 */
public final class OtlpProfileValidator {

  private OtlpProfileValidator() {
    // Utility class
  }

  /**
   * Validates dictionary table constraints according to OTLP spec.
   *
   * @param strings string table
   * @param functions function table
   * @param locations location table
   * @param stacks stack table
   * @param links link table
   * @param attributes attribute table
   * @return validation result with any errors or warnings found
   */
  public static ValidationResult validateDictionaries(
      StringTable strings,
      FunctionTable functions,
      LocationTable locations,
      StackTable stacks,
      LinkTable links,
      AttributeTable attributes) {

    ValidationResult.Builder result = ValidationResult.builder();

    // Validate StringTable
    validateStringTable(strings, result);

    // Validate FunctionTable
    validateFunctionTable(functions, strings, result);

    // Validate LocationTable
    validateLocationTable(locations, functions, result);

    // Validate StackTable
    validateStackTable(stacks, locations, result);

    // Validate LinkTable
    validateLinkTable(links, result);

    // Validate AttributeTable
    validateAttributeTable(attributes, strings, result);

    return result.build();
  }

  /**
   * Validates string table constraints.
   *
   * @param strings the string table
   * @param result the validation result builder
   */
  private static void validateStringTable(StringTable strings, ValidationResult.Builder result) {
    // Check that table is not empty
    if (strings.size() == 0) {
      result.addError("StringTable is empty - must have at least index 0 (empty string)");
      return;
    }

    // Check that index 0 is empty string (null/unset sentinel)
    String index0 = strings.get(0);
    if (index0 == null) {
      result.addError("StringTable index 0 is null - should be empty string (\"\")");
    } else if (!index0.isEmpty()) {
      result.addError(
          "StringTable index 0 is not empty string - found: \""
              + index0
              + "\" (length "
              + index0.length()
              + ")");
    }

    // Check for duplicate strings (except index 0)
    for (int i = 1; i < strings.size(); i++) {
      String s = strings.get(i);
      for (int j = i + 1; j < strings.size(); j++) {
        if (s.equals(strings.get(j))) {
          result.addWarning(
              "StringTable has duplicate entries: index "
                  + i
                  + " and "
                  + j
                  + " both contain \""
                  + s
                  + "\"");
        }
      }
    }
  }

  /**
   * Validates function table constraints.
   *
   * @param functions the function table
   * @param strings the string table (for reference validation)
   * @param result the validation result builder
   */
  private static void validateFunctionTable(
      FunctionTable functions, StringTable strings, ValidationResult.Builder result) {

    if (functions.size() == 0) {
      result.addError("FunctionTable is empty - must have at least index 0 (null/unset)");
      return;
    }

    // Validate that all function string indices reference valid strings
    for (int i = 0; i < functions.size(); i++) {
      FunctionTable.FunctionEntry entry = functions.get(i);

      // Check name index
      if (entry.nameIndex < 0 || entry.nameIndex >= strings.size()) {
        result.addError(
            "FunctionTable entry "
                + i
                + " has invalid nameIndex "
                + entry.nameIndex
                + " (StringTable size: "
                + strings.size()
                + ")");
      }

      // Check system name index
      if (entry.systemNameIndex < 0 || entry.systemNameIndex >= strings.size()) {
        result.addError(
            "FunctionTable entry "
                + i
                + " has invalid systemNameIndex "
                + entry.systemNameIndex
                + " (StringTable size: "
                + strings.size()
                + ")");
      }

      // Check filename index
      if (entry.filenameIndex < 0 || entry.filenameIndex >= strings.size()) {
        result.addError(
            "FunctionTable entry "
                + i
                + " has invalid filenameIndex "
                + entry.filenameIndex
                + " (StringTable size: "
                + strings.size()
                + ")");
      }
    }
  }

  /**
   * Validates location table constraints.
   *
   * @param locations the location table
   * @param functions the function table (for reference validation)
   * @param result the validation result builder
   */
  private static void validateLocationTable(
      LocationTable locations, FunctionTable functions, ValidationResult.Builder result) {

    if (locations.size() == 0) {
      result.addError("LocationTable is empty - must have at least index 0 (null/unset)");
      return;
    }

    // Validate that all location line entries reference valid functions
    for (int i = 0; i < locations.size(); i++) {
      LocationTable.LocationEntry entry = locations.get(i);

      // Check line entries if present
      if (entry.lines != null && !entry.lines.isEmpty()) {
        for (int lineIdx = 0; lineIdx < entry.lines.size(); lineIdx++) {
          LocationTable.LineEntry line = entry.lines.get(lineIdx);
          if (line.functionIndex < 0 || line.functionIndex >= functions.size()) {
            result.addError(
                "LocationTable entry "
                    + i
                    + " line "
                    + lineIdx
                    + " has invalid functionIndex "
                    + line.functionIndex
                    + " (FunctionTable size: "
                    + functions.size()
                    + ")");
          }
        }
      }
    }
  }

  /**
   * Validates stack table constraints.
   *
   * @param stacks the stack table
   * @param locations the location table (for reference validation)
   * @param result the validation result builder
   */
  private static void validateStackTable(
      StackTable stacks, LocationTable locations, ValidationResult.Builder result) {

    if (stacks.size() == 0) {
      result.addError("StackTable is empty - must have at least index 0 (null/unset)");
      return;
    }

    // Check that index 0 is empty stack
    StackTable.StackEntry index0 = stacks.get(0);
    if (index0.locationIndices == null || index0.locationIndices.length != 0) {
      result.addError("StackTable index 0 must be empty stack (zero-length array)");
    }

    // Validate that all stack location indices reference valid locations
    for (int i = 0; i < stacks.size(); i++) {
      StackTable.StackEntry entry = stacks.get(i);
      if (entry.locationIndices != null) {
        for (int j = 0; j < entry.locationIndices.length; j++) {
          int locationIndex = entry.locationIndices[j];
          if (locationIndex < 0 || locationIndex >= locations.size()) {
            result.addError(
                "StackTable entry "
                    + i
                    + " location "
                    + j
                    + " has invalid index "
                    + locationIndex
                    + " (LocationTable size: "
                    + locations.size()
                    + ")");
          }
        }
      }
    }
  }

  /**
   * Validates link table constraints.
   *
   * @param links the link table
   * @param result the validation result builder
   */
  private static void validateLinkTable(LinkTable links, ValidationResult.Builder result) {
    if (links.size() == 0) {
      result.addError("LinkTable is empty - must have at least index 0 (null/unset)");
      return;
    }

    // Check that index 0 has zero trace/span IDs
    LinkTable.LinkEntry index0 = links.get(0);
    if (!isZeroBytes(index0.traceId) || !isZeroBytes(index0.spanId)) {
      result.addError(
          "LinkTable index 0 must have zero trace_id and span_id (null/unset sentinel)");
    }

    // Validate that all non-zero links have non-zero trace_id and span_id
    for (int i = 1; i < links.size(); i++) {
      LinkTable.LinkEntry entry = links.get(i);

      if (isZeroBytes(entry.traceId)) {
        result.addWarning("LinkTable entry " + i + " has zero trace_id (should be non-zero)");
      }

      if (isZeroBytes(entry.spanId)) {
        result.addWarning("LinkTable entry " + i + " has zero span_id (should be non-zero)");
      }
    }
  }

  /**
   * Validates attribute table constraints.
   *
   * @param attributes the attribute table
   * @param strings the string table (for reference validation)
   * @param result the validation result builder
   */
  private static void validateAttributeTable(
      AttributeTable attributes, StringTable strings, ValidationResult.Builder result) {

    if (attributes.size() == 0) {
      result.addError("AttributeTable is empty - must have at least index 0 (null/unset)");
      return;
    }

    // Validate that all attribute key indices reference valid strings
    for (int i = 0; i < attributes.size(); i++) {
      AttributeTable.AttributeEntry entry = attributes.get(i);

      // Check key index
      if (entry.keyIndex < 0 || entry.keyIndex >= strings.size()) {
        result.addError(
            "AttributeTable entry "
                + i
                + " has invalid keyIndex "
                + entry.keyIndex
                + " (StringTable size: "
                + strings.size()
                + ")");
      }

      // Check unit index
      if (entry.unitIndex < 0 || entry.unitIndex >= strings.size()) {
        result.addError(
            "AttributeTable entry "
                + i
                + " has invalid unitIndex "
                + entry.unitIndex
                + " (StringTable size: "
                + strings.size()
                + ")");
      }

      // Note: For STRING type, the value is stored as a String object, not an index into
      // StringTable
      // INT, BOOL, and DOUBLE types store their values directly as Object
    }
  }

  /**
   * Checks if byte array is all zeros.
   *
   * @param bytes the byte array
   * @return true if all bytes are zero
   */
  private static boolean isZeroBytes(byte[] bytes) {
    if (bytes == null) {
      return true;
    }
    for (byte b : bytes) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}
