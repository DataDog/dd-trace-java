package datadog.trace.api.config;

/** Constant with names of configuration options for IAST. */
public final class IastConfig {

  public static final String IAST_ENABLED = "iast.enabled";
  public static final String IAST_WEAK_HASH_ALGORITHMS = "iast.weak-hash.algorithms";
  public static final String IAST_WEAK_CIPHER_ALGORITHMS = "iast.weak-cipher.algorithms";
  public static final String IAST_MAX_CONCURRENT_REQUESTS = "iast.max-concurrent-request";
  public static final String IAST_VULNERABILITIES_PER_REQUEST = "iast.vulnerabilities-per-request";
  public static final String IAST_REQUEST_SAMPLING = "iast.request-sampling";
  public static final String IAST_DEDUPLICATION_ENABLED = "iast.deduplication.enabled";

  private IastConfig() {}
}
