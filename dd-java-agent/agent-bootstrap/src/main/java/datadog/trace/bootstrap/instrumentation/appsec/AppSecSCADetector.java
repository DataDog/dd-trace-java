package datadog.trace.bootstrap.instrumentation.appsec;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackTraceEvent;
import datadog.trace.util.stacktrace.StackTraceFrame;
import datadog.trace.util.stacktrace.StackUtils;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SCA (Supply Chain Analysis) detection handler.
 *
 * <p>This class is called from instrumented bytecode when vulnerable library methods are invoked.
 * It must be in the bootstrap classloader to be accessible from any instrumented class.
 *
 * <p>Adds vulnerability metadata to the root span for backend reporting and logs detections at
 * debug level.
 */
public class AppSecSCADetector {

  private static final Logger log = LoggerFactory.getLogger(AppSecSCADetector.class);

  private static final String METASTRUCT_SCA = "sca";

  private static final String PREFIX = "_dd.appsec.sca.";

  /**
   * Called when a vulnerable method is invoked.
   *
   * <p>This method is invoked from instrumented bytecode injected by {@code AppSecSCATransformer}.
   *
   * @param className The internal class name (e.g., "com/example/Foo")
   * @param methodName The method name
   * @param descriptor The method descriptor
   * @param advisory The advisory ID (e.g., "GHSA-77xx-rxvh-q682"), may be null
   * @param cve The CVE ID (e.g., "CVE-2022-41853"), may be null
   */
  public static void onMethodInvocation(
      String className, String methodName, String descriptor, String advisory, String cve) {
    try {
      // Convert internal class name to binary name for readability
      String binaryClassName = className.replace('/', '.');

      // Get the active span and add tags to root span
      AgentSpan activeSpan = AgentTracer.activeSpan();
      if (activeSpan != null) {
        AgentSpan rootSpan = activeSpan.getLocalRootSpan();
        if (rootSpan != null) {
          // Tag the root span with SCA detection metadata
          rootSpan.setTag(PREFIX + "class", binaryClassName);
          rootSpan.setTag(PREFIX + "method", methodName);

          if (advisory != null) {
            rootSpan.setTag(PREFIX + "advisory", advisory);
          }
          if (cve != null) {
            rootSpan.setTag(PREFIX + "cve", cve);
          }

          // Capture and add stack trace using IAST's system
          String stackId = addSCAStackTrace(rootSpan);
          if (stackId != null) {
            rootSpan.setTag(PREFIX + "stack_id", stackId);
          }
        }
      }

      // Log at debug level
      log.debug(
          "SCA detection: {} - Vulnerable method invoked: {}#{}", cve, binaryClassName, methodName);

      // TODO: Future enhancements:
      // - Report Location
      // - Report multiple vulnerabilities per request
      // - Implement rate limiting to avoid log spam
      // - Add sampling for high-frequency methods

    } catch (Throwable t) {
      // Never throw from instrumented callback - would break application
      // Silently ignore errors
      log.debug("Error in SCA detection handler", t);
    }
  }

  /**
   * Captures and adds the current stack trace to the meta struct using IAST's system.
   *
   * <p>Uses the same stacktrace mechanism as IAST to store frames as structured data in the meta
   * struct, which will be serialized as an array in the backend.
   *
   * @param span the span to attach the stack trace to
   * @return the stack ID if successful, null otherwise
   */
  private static String addSCAStackTrace(AgentSpan span) {
    try {
      final RequestContext reqCtx = span.getRequestContext();
      if (reqCtx == null) {
        return null;
      }

      // Generate user code stack trace (filters out Datadog internal frames)
      List<StackTraceFrame> frames = StackUtils.generateUserCodeStackTrace();
      if (frames == null || frames.isEmpty()) {
        return null;
      }

      // Create a stack trace event with a unique ID
      // Use timestamp + thread ID to create a reasonably unique ID
      String stackId = "sca_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
      StackTraceEvent stackTraceEvent =
          new StackTraceEvent(frames, StackTraceEvent.DEFAULT_LANGUAGE, stackId, null);

      // Add to meta struct using the same system as IAST
      StackUtils.addStacktraceEventsToMetaStruct(
          reqCtx, METASTRUCT_SCA, Collections.singletonList(stackTraceEvent));

      return stackId;

    } catch (Throwable t) {
      // Never throw from instrumented callback - would break application
      log.debug("Failed to capture SCA stack trace", t);
      return null;
    }
  }
}
