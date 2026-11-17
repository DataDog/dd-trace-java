package com.datadog.openfeature.internal.ufc;

public final class ConditionConfiguration {
  public final ConditionOperator operator;
  public final String attribute;
  public final Object value;

  // The validated, parsed SemVer condition value. Set during configuration preprocessing
  // (not from JSON) when the operator is a SEMVER_* operator.
  public transient ParsedSemver semverComparand;

  // The validated MATCHES/NOT_MATCHES matcher, prepared when the configuration is parsed.
  public transient RegexMatcher regex;

  // The ONE_OF/NOT_ONE_OF hash index, built when the configuration is parsed; null for one-element
  // lists and for configurations that are not parsed.
  public transient MembershipIndex membership;

  public ConditionConfiguration(
      final ConditionOperator operator, final String attribute, final Object value) {
    this.operator = operator;
    this.attribute = attribute;
    this.value = value;
  }

  /**
   * Compiles a MATCHES/NOT_MATCHES pattern.
   *
   * @throws java.util.regex.PatternSyntaxException if the pattern is not valid in the Rust {@code
   *     regex} dialect.
   */
  public static RegexMatcher compileRegex(final Object value) {
    return RegexMatcher.compile(String.valueOf(value));
  }
}
