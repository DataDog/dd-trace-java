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
          rootSpan.setTag("appsec.sca.class", binaryClassName);
          rootSpan.setTag("appsec.sca.method", methodName);

          if (advisory != null) {
            rootSpan.setTag("appsec.sca.advisory", advisory);
          }
          if (cve != null) {
            rootSpan.setTag("appsec.sca.cve", cve);
          }

          // Capture and add stack trace using IAST's system
          String stackId = addSCAStackTrace(rootSpan);
          if (stackId != null) {
            rootSpan.setTag("appsec.sca.stack_id", stackId);
          }

          // Capture and add location
          SCALocation location = captureLocation(rootSpan, stackId);
          if (location != null) {
            addLocationTags(rootSpan, location);
          }
        }
      }

      // Build detection message with vulnerability metadata
      StringBuilder message = new StringBuilder("SCA detection: Vulnerable method invoked: ");
      message.append(binaryClassName).append(".").append(methodName).append(descriptor);

      if (advisory != null || cve != null) {
        message.append(" [");
        if (advisory != null) {
          message.append("Advisory: ").append(advisory);
        }
        if (cve != null) {
          if (advisory != null) {
            message.append(", ");
          }
          message.append("CVE: ").append(cve);
        }
        message.append("]");
      }

      // Log at debug level
      log.debug(message.toString());

      // TODO: Future enhancements:
      // - Report to Datadog backend via telemetry API
      // - Implement rate limiting to avoid log spam
      // - Add sampling for high-frequency methods

    } catch (Throwable t) {
      // Never throw from instrumented callback - would break application
      // Silently ignore errors
      log.debug("Error in SCA detection handler", t);
    }
  }

  /**
   * Captures the location where the vulnerable method was invoked.
   *
   * @param span the span
   * @param stackId the stack trace ID
   * @return the location, or null if unable to capture
   */
  private static SCALocation captureLocation(AgentSpan span, String stackId) {
    try {
      // Get the first user code frame from the stack trace
      StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

      for (StackTraceElement element : stackTrace) {
        String className = element.getClassName();

        // Skip internal frames
        if (className.startsWith("java.lang.Thread")
            || className.startsWith("datadog.trace.bootstrap.instrumentation.appsec.")
            || className.contains("AppSecSCATransformer$")) {
          continue;
        }

        // Found first user code frame
        return SCALocation.forSpanAndStack(span, element, stackId);
      }

      return null;
    } catch (Throwable t) {
      log.debug("Failed to capture SCA location", t);
      return null;
    }
  }

  /**
   * Adds location information as tags to the span.
   *
   * @param span the span to tag
   * @param location the location information
   */
  private static void addLocationTags(AgentSpan span, SCALocation location) {
    if (location.getPath() != null) {
      span.setTag("appsec.sca.location.path", location.getPath());
    }
    if (location.getMethod() != null) {
      span.setTag("appsec.sca.location.method", location.getMethod());
    }
    if (location.getLine() > 0) {
      span.setTag("appsec.sca.location.line", location.getLine());
    }
    if (location.getSpanId() != null) {
      span.setTag("appsec.sca.location.span_id", location.getSpanId());
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
