package datadog.trace.api.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Constant with names of configuration options for IAST. */
public final class IastConfig {
  public static final String IAST_ENABLED = "iast.enabled";
  public static final String IAST_WEAK_HASH_ALGORITHMS = "iast.weak-hash.algorithms";
  public static final String IAST_WEAK_CIPHER_ALGORITHMS = "iast.weak-cipher.algorithms";
  public static final String IAST_MAX_CONCURRENT_REQUESTS = "iast.max-concurrent-request";
  public static final String IAST_VULNERABILITIES_PER_REQUEST = "iast.vulnerabilities-per-request";
  public static final String IAST_REQUEST_SAMPLING = "iast.request-sampling";
  public static final String IAST_DEDUPLICATION_ENABLED = "iast.deduplication.enabled";

  static final boolean DEFAULT_IAST_ENABLED = false;
  public static final int DEFAULT_IAST_MAX_CONCURRENT_REQUESTS = 2;
  public static final int DEFAULT_IAST_VULNERABILITIES_PER_REQUEST = 2;
  public static final int DEFAULT_IAST_REQUEST_SAMPLING = 30;
  static final Set<String> DEFAULT_IAST_WEAK_HASH_ALGORITHMS =
      new HashSet<>(Arrays.asList("MD2", "MD5", "RIPEMD128", "MD4"));
  static final String DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS =
      "^(?:PBEWITH(?:HMACSHA(?:2(?:24ANDAES_(?:128|256)|56ANDAES_(?:128|256))|384ANDAES_(?:128|256)|512ANDAES_(?:128|256)|1ANDAES_(?:128|256))|SHA1AND(?:RC(?:2_(?:128|40)|4_(?:128|40))|DESEDE)|MD5AND(?:TRIPLEDES|DES))|DES(?:EDE(?:WRAP)?)?|BLOWFISH|ARCFOUR|RC2).*$";

  static final boolean DEFAULT_IAST_DEDUPLICATION_ENABLED = true;

  private IastConfig() {}
}
