package datadog.trace.api.config;

/** Constant with names of configuration options for IAST. */
public final class IastConfig {

  public static final String IAST_ENABLED = "iast.enabled";
  public static final String IAST_WEAK_HASH_ALGORITHMS = "iast.weak-hash.algorithms";
  public static final String IAST_WEAK_CIPHER_ALGORITHMS = "iast.weak-cipher.algorithms";
  public static final String IAST_DEBUG_ENABLED = "iast.debug.enabled";
  public static final String IAST_MAX_CONCURRENT_REQUESTS = "iast.max-concurrent-requests";
  public static final String IAST_VULNERABILITIES_PER_REQUEST = "iast.vulnerabilities-per-request";
  public static final String IAST_REQUEST_SAMPLING = "iast.request-sampling";
  public static final String IAST_DEDUPLICATION_ENABLED = "iast.deduplication.enabled";
  public static final String IAST_TELEMETRY_VERBOSITY = "iast.telemetry.verbosity";
  public static final String IAST_DETECTION_MODE = "iast.detection.mode";
  public static final String IAST_REDACTION_ENABLED = "iast.redaction.enabled";
  public static final String IAST_REDACTION_NAME_PATTERN = "iast.redaction.name.pattern";
  public static final String IAST_REDACTION_VALUE_PATTERN = "iast.redaction.value.pattern";
  public static final String IAST_STACKTRACE_LEAK_SUPPRESS = "iast.stacktrace-leak.suppress";

  public static final String IAST_HARDCODED_SECRET_ENABLED = "iast.hardcoded-secret.enabled";
  public static final String IAST_MAX_RANGE_COUNT = "iast.max-range-count";
  public static final String IAST_TRUNCATION_MAX_VALUE_LENGTH = "iast.truncation.max.value.length";
  public static final String IAST_CONTEXT_MODE = "iast.context.mode";
  public static final String IAST_ANONYMOUS_CLASSES_ENABLED = "iast.anonymous-classes.enabled";

  private IastConfig() {}
}
