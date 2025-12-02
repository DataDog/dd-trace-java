package datadog.trace.bootstrap.instrumentation.appsec;

/**
 * SCA (Supply Chain Analysis) detection handler.
 *
 * <p>This class is called from instrumented bytecode when vulnerable library methods are invoked.
 * It must be in the bootstrap classloader to be accessible from any instrumented class.
 *
 * <p>POC implementation: Logs detections to stderr. Production implementation would report to
 * Datadog backend via telemetry.
 */
public class AppSecSCADetector {

  /**
   * Called when a vulnerable method is invoked.
   *
   * <p>This method is invoked from instrumented bytecode injected by {@code
   * AppSecSCATransformer}.
   *
   * @param className The internal class name (e.g., "com/example/Foo")
   * @param methodName The method name
   * @param descriptor The method descriptor
   */
  public static void onMethodInvocation(String className, String methodName, String descriptor) {
    try {
      // Convert internal class name to binary name for readability
      String binaryClassName = className.replace('/', '.');

      // Log to stderr (visible in application logs)
      System.err.println(
          "[SCA DETECTION] Vulnerable method invoked: "
              + binaryClassName
              + "."
              + methodName
              + descriptor);

      // TODO: Future enhancements:
      // - Capture stack trace for context
      // - Add CVE metadata from instrumentation config
      // - Report to Datadog backend via telemetry API
      // - Implement rate limiting to avoid log spam
      // - Add sampling for high-frequency methods

    } catch (Throwable t) {
      // Never throw from instrumented callback - would break application
      // Silently ignore errors
    }
  }
}
