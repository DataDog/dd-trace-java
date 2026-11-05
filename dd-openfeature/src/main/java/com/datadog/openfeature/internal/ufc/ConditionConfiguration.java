package com.datadog.openfeature.internal.ufc;

import java.util.regex.Pattern;

public final class ConditionConfiguration {
  public final ConditionOperator operator;
  public final String attribute;
  public final Object value;

  // The validated, parsed SemVer condition value. Set during configuration preprocessing
  // (not from JSON) when the operator is a SEMVER_* operator.
  public transient ParsedSemver semverComparand;

  // The validated MATCHES/NOT_MATCHES pattern, prepared when the configuration is parsed.
  public transient Pattern regex;

  public ConditionConfiguration(
      final ConditionOperator operator, final String attribute, final Object value) {
    this.operator = operator;
    this.attribute = attribute;
    this.value = value;
  }

  /** Compiles the Java pattern after translating the supported POSIX character classes. */
  public static Pattern compileRegex(final Object value) {
    return Pattern.compile(normalizeRegex(String.valueOf(value)));
  }

  private static String normalizeRegex(final String regex) {
    return regex
        .replace("[:alnum:]", "\\p{Alnum}")
        .replace("[:alpha:]", "\\p{Alpha}")
        .replace("[:digit:]", "\\p{Digit}")
        .replace("[:lower:]", "\\p{Lower}")
        .replace("[:upper:]", "\\p{Upper}")
        .replace("[:space:]", "\\p{Space}");
  }
}
