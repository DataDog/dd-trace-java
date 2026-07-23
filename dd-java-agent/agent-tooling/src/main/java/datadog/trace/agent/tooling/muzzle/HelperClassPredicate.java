package datadog.trace.agent.tooling.muzzle;

import datadog.trace.bootstrap.Constants;
import java.util.function.Predicate;

/**
 * Classifies a referenced class as an injectable tracer helper, a bootstrap class, or a library
 * class — similar to OpenTelemetry's {@code HelperClassPredicate#isHelperClass}; however, the main
 * signal here is whether the class was compiled from the instrumentation subproject's own output
 * ({@code ownOutput}). {@link #HELPER_PREFIXES} additionally covers helpers that live in other
 * tracer subprojects.
 */
public final class HelperClassPredicate {

  static final String[] HELPER_PREFIXES = {
    "datadog.trace.instrumentation.",
    "datadog.opentelemetry.shim.",
    "datadog.trace.agent.tooling.iast.",
    "datadog.trace.agent.tooling.nativeimage.",
  };

  private final Predicate<String> ownOutput;

  /**
   * @param ownOutput whether a dotted class name was compiled from the instrumentation subproject's
   *     own output; injected because agent-tooling cannot resolve build directories itself.
   */
  public HelperClassPredicate(final Predicate<String> ownOutput) {
    this.ownOutput = ownOutput;
  }

  public boolean isHelperClass(final String className) {
    return !isBootstrap(className) && (ownOutput.test(className) || matchesHelperPrefix(className));
  }

  private static boolean matchesHelperPrefix(final String className) {
    for (final String prefix : HELPER_PREFIXES) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the class is on the bootstrap class-path (or a JDK/SLF4J type) and so never injected.
   */
  public static boolean isBootstrap(final String className) {
    if (className.startsWith("java.")
        || className.startsWith("javax.")
        || className.startsWith("jdk.")
        || className.startsWith("com.sun.")
        || className.startsWith("sun.")
        || className.startsWith("org.slf4j.")
        || className.startsWith("datadog.slf4j.")) {
      return true;
    }
    for (final String prefix : Constants.BOOTSTRAP_PACKAGE_PREFIXES) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
