package datadog.trace.agent.test.scopediag;

/**
 * Maps the {@code byte} scope source used by the tracer to a readable name. Mirrors the constants
 * in {@code datadog.trace.core.scopemanager.ContinuableScope} (which are package-private and not
 * visible from here).
 */
final class ScopeSources {
  private ScopeSources() {}

  static String name(byte source) {
    switch (source) {
      case 0:
        return "INSTRUMENTATION";
      case 1:
        return "MANUAL";
      case 2:
        return "ITERATION";
      case 3:
        return "CONTEXT";
      default:
        return "UNKNOWN(" + source + ")";
    }
  }
}
