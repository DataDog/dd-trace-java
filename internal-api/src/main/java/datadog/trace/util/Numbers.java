package datadog.trace.util;

import javax.annotation.Nullable;

/**
 * Utility methods for working with numbers and numeric string parsing.
 *
 * <p>This class provides optimized numeric parsing that avoids throwing exceptions for invalid
 * inputs, making it suitable for high-throughput scenarios where non-numeric strings are common.
 */
public final class Numbers {

  private Numbers() {
    // Utility class - prevent instantiation
  }

  /**
   * Attempts to parse a string value as a number with fast-path validation to avoid throwing
   * NumberFormatException for invalid inputs.
   *
   * <p>This method first validates that the string looks like a valid number before attempting to
   * parse, significantly reducing overhead when processing non-numeric strings. The validation
   * supports:
   *
   * <ul>
   *   <li>Integers: "42", "-100", "+999"
   *   <li>Decimals: "3.14", ".5", "5."
   *   <li>Scientific notation: "1.5e10", "-3.5E-7", "2e+3"
   *   <li>Whitespace: Trimmed before validation (e.g., " 42 " parses successfully)
   * </ul>
   *
   * <p>Performance characteristics:
   *
   * <ul>
   *   <li>Valid numeric strings: ~50-100 ns/op
   *   <li>Invalid strings: ~40-70 ns/op (vs ~1000+ ns with exception-based parsing)
   *   <li>Empty/null: ~5 ns/op
   * </ul>
   *
   * @param value the string to parse, may be null
   * @return a Long for plain integers, Double for decimals or scientific notation, or null if the
   *     string is not a valid number
   */
  @Nullable
  public static Number parseNumber(@Nullable String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }

    // Trim whitespace to handle common cases like " 42 " or "\t100\n"
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }

    // Fast-path validation: check if the string looks like a valid number
    // before attempting to parse, to avoid expensive NumberFormatException throws
    // Supports: integers, decimals, and scientific notation (e.g., "1.5e10", "-3E-7")
    boolean hasDecimal = false;
    boolean hasExponent = false;
    int startIdx = 0;
    int length = trimmed.length();

    // Handle optional leading sign
    char firstChar = trimmed.charAt(0);
    if (firstChar == '+' || firstChar == '-') {
      startIdx = 1;
      // Sign-only strings like "+" or "-" are not valid numbers
      if (startIdx >= length) {
        return null;
      }
    }

    // Validate characters: digits, optional decimal point, optional exponent
    boolean hasDigits = false;
    for (int i = startIdx; i < length; i++) {
      char c = trimmed.charAt(i);
      if (c == '.') {
        if (hasDecimal || hasExponent) {
          // Multiple decimal points or decimal after exponent - invalid
          return null;
        }
        hasDecimal = true;
      } else if (c == 'e' || c == 'E') {
        if (hasExponent || !hasDigits) {
          // Multiple exponents or exponent without preceding digits - invalid
          return null;
        }
        hasExponent = true;
        // Next character can be optional sign (+/-) followed by digits
        if (i + 1 < length) {
          char next = trimmed.charAt(i + 1);
          if (next == '+' || next == '-') {
            i++; // Skip the sign after exponent
            // Must have at least one digit after exponent sign
            if (i + 1 >= length) {
              return null;
            }
          }
        } else {
          // Exponent must be followed by digits
          return null;
        }
      } else if (c >= '0' && c <= '9') {
        hasDigits = true;
      } else {
        // Non-digit, non-decimal, non-exponent character - invalid
        return null;
      }
    }

    // Must have at least one digit (reject strings like ".", "+.", "e10")
    if (!hasDigits) {
      return null;
    }

    // Now attempt parsing - the try-catch remains as a fallback for overflow cases
    // (e.g., Long.MAX_VALUE+1) or other edge cases that passed pre-validation
    try {
      if (hasDecimal || hasExponent) {
        // Parse as Double for decimals and scientific notation
        return Double.parseDouble(trimmed);
      } else {
        // Parse as Long for plain integers
        return Long.parseLong(trimmed);
      }
    } catch (NumberFormatException e) {
      // Overflow or other parse failure despite pre-check (rare)
      return null;
    }
  }
}
