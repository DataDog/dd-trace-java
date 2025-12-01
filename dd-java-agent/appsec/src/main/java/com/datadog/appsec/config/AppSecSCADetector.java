package com.datadog.appsec.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detection handler for Supply Chain Analysis (SCA) vulnerability detection.
 *
 * <p>This class is called from instrumented methods to report when vulnerable third-party
 * library methods are invoked at runtime.
 *
 * <p>POC implementation: Currently just logs detections. Future versions will report to Datadog
 * backend with vulnerability metadata, stack traces, and context.
 */
public class AppSecSCADetector {

  private static final Logger log = LoggerFactory.getLogger(AppSecSCADetector.class);

  /**
   * Called when an instrumented SCA target method is invoked.
   *
   * <p>This method is invoked from bytecode injected by {@link AppSecSCATransformer}.
   *
   * @param className the internal class name (e.g., "org/springframework/web/client/RestTemplate")
   * @param methodName the method name (e.g., "execute")
   * @param descriptor the method descriptor (e.g., "(Ljava/lang/String;)V")
   */
  public static void onMethodInvocation(String className, String methodName, String descriptor) {
    try {
      // POC: Log the detection
      // Future: Report to Datadog backend with vulnerability context
      log.info(
          "[SCA DETECTION] Vulnerable method invoked: {}.{}{}",
          className.replace('/', '.'),
          methodName,
          descriptor);

      // TODO: Future enhancements:
      // - Capture stack trace
      // - Add vulnerability metadata (CVE ID, severity, etc.)
      // - Report to Datadog backend via telemetry
      // - Rate limiting to avoid log spam
      // - Include request context if available

    } catch (Throwable t) {
      // Catch all exceptions to avoid breaking the instrumented method
      log.error("Error in SCA detection handler", t);
    }
  }
}
