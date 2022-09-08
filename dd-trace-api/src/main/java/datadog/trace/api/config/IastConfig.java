package datadog.trace.api.config;

/** Constant with names of configuration options for IAST. */
public final class IastConfig {

  public static final String IAST_ENABLED = "iast.enabled";
  public static final String IAST_WEAK_HASH_ALGORITHMS = "iast.weak-hash.algorithms";
  public static final String IAST_WEAK_CIPHER_ALGORITHMS = "iast.weak-cipher.algorithms";

  private IastConfig() {}
}
