package datadog.trace.agent.test.scopediag;

/** Maps package-private {@code ContinuableScope} source values to readable names. */
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
