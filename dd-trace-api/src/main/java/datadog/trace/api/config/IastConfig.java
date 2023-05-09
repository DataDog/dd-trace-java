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

  private IastConfig() {}
}
