package datadog.trace.api.featureflag.ufc.v1;

import java.util.regex.Pattern;

public class ConditionConfiguration {
  public final ConditionOperator operator;
  public final String attribute;
  public final Object value;

  // The validated, parsed SemVer condition value. Set during configuration preprocessing
  // (not from JSON) when the operator is a SEMVER_* operator.
  public transient ParsedSemver semverComparand;

  // The compiled MATCHES or NOT_MATCHES value. Set during configuration preprocessing. Pattern is
  // immutable and safe to share between concurrent evaluation threads.
  private transient Pattern regexPattern;

  public ConditionConfiguration(
      final ConditionOperator operator, final String attribute, final Object value) {
    this.operator = operator;
    this.attribute = attribute;
    this.value = value;
  }

  /** Compiles and caches this condition's normalized regular expression. */
  public void cacheRegexPattern() {
    regexPattern = compileRegex();
  }

  /** Returns the cached pattern, or compiles one for a condition created outside the UFC parser. */
  public Pattern regexPattern() {
    final Pattern cached = regexPattern;
    return cached != null ? cached : compileRegex();
  }

  /** Returns true when configuration preprocessing cached this condition's pattern. */
  public boolean hasCachedRegexPattern() {
    return regexPattern != null;
  }

  private Pattern compileRegex() {
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
