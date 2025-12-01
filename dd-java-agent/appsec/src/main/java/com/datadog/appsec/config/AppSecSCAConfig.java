package com.datadog.appsec.config;

import com.squareup.moshi.Json;
import java.util.List;

/**
 * Configuration model for Supply Chain Analysis (SCA) vulnerability detection.
 * Received via Remote Config in the ASM_SCA product.
 *
 * <p>This configuration enables dynamic instrumentation of third-party dependencies
 * to detect and report known vulnerabilities at runtime.
 */
public class AppSecSCAConfig {

  /**
   * Whether SCA vulnerability detection is enabled.
   */
  @Json(name = "enabled")
  public Boolean enabled;

  /**
   * List of instrumentation targets for SCA analysis.
   * Each target specifies a class/method to instrument for vulnerability detection.
   */
  @Json(name = "instrumentation_targets")
  public List<InstrumentationTarget> instrumentationTargets;

  /**
   * Represents a single instrumentation target for SCA.
   */
  public static class InstrumentationTarget {
    /**
     * Fully qualified class name in internal format (e.g., "org/springframework/web/client/RestTemplate").
     */
    @Json(name = "class_name")
    public String className;

    /**
     * Method name to instrument (e.g., "execute").
     */
    @Json(name = "method_name")
    public String methodName;
  }
}