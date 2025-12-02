package com.datadog.appsec.config;

import com.squareup.moshi.Json;
import java.util.List;

/**
 * Configuration model for SCA vulnerability detection. Received via Remote Config in the ASM_SCA
 * product.
 *
 * <p>This configuration enables dynamic instrumentation of third-party dependencies to detect and
 * report known vulnerabilities at runtime. Each vulnerability specifies:
 *
 * <ul>
 *   <li>Advisory and CVE identifiers
 *   <li>Vulnerable internal code location (class/method to instrument)
 *   <li>External entrypoints that can trigger the vulnerability
 * </ul>
 */
public class AppSecSCAConfig {

  /** List of vulnerabilities to detect via instrumentation. */
  @Json(name = "vulnerabilities")
  public List<Vulnerability> vulnerabilities;

  /** Represents a single vulnerability with its detection metadata. */
  public static class Vulnerability {
    /** GitHub Security Advisory ID (e.g., "GHSA-24rp-q3w6-vc56"). */
    @Json(name = "advisory")
    public String advisory;

    /** CVE identifier (e.g., "CVE-2024-1597"). */
    @Json(name = "cve")
    public String cve;

    /**
     * The vulnerable internal code location to instrument. This is where the actual vulnerability
     * exists in the dependency.
     */
    @Json(name = "vulnerable_internal_code")
    public CodeLocation vulnerableInternalCode;

    /**
     * External entrypoint(s) that can trigger the vulnerability. These are the public API methods
     * that users call which eventually reach the vulnerable code.
     */
    @Json(name = "external_entrypoint")
    public ExternalEntrypoint externalEntrypoint;
  }

  /** Represents a code location (class + method) to instrument. */
  public static class CodeLocation {
    /**
     * Fully qualified class name in binary format (e.g.,
     * "org.postgresql.core.v3.SimpleParameterList").
     */
    @Json(name = "class")
    public String className;

    /** Method name (e.g., "toString"). */
    @Json(name = "method")
    public String methodName;
  }

  /** Represents external entrypoint(s) for a vulnerability. */
  public static class ExternalEntrypoint {
    /**
     * Fully qualified class name in binary format (e.g.,
     * "org.postgresql.jdbc.PgPreparedStatement").
     */
    @Json(name = "class")
    public String className;

    /** List of method names that serve as entrypoints (e.g., ["executeQuery", "executeUpdate"]). */
    @Json(name = "methods")
    public List<String> methods;
  }
}
