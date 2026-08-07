package datadog.trace.agent.tooling.muzzle;

import datadog.trace.bootstrap.Constants;
import java.util.function.Predicate;

/**
 * Classifies a referenced class as an injectable tracer helper, a bootstrap class, or a library
 * class — similar to OpenTelemetry's {@code HelperClassPredicate#isHelperClass}. The primary signal
 * is {@code ownOutput}: a class the instrumentation subproject compiled itself.
 *
 * <p>A subproject only injects helpers it owns; a helper owned by another subproject must be
 * declared explicitly via {@code helperClassNames()}. {@link #HELPER_PREFIXES} lists the shared
 * infrastructure subprojects that are not owned by a specific subproject and so are always treated
 * as helpers.
 */
public final class HelperClassPredicate {

  static final String[] HELPER_PREFIXES = {
    "datadog.opentelemetry.shim.",
    "datadog.trace.agent.tooling.iast.",
    "datadog.trace.agent.tooling.nativeimage.",
  };

  private final Predicate<String> ownOutput;

  /**
   * @param ownOutput tests whether a class name was compiled by the instrumentation subproject
   *     itself; injected so this classifier stays independent of the build directory layout.
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

  /** Whether the class is on the bootstrap class-path and so never injected. */
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
