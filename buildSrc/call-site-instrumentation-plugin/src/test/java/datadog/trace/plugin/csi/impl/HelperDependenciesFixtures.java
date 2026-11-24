package datadog.trace.plugin.csi.impl;

/**
 * Classes {@link HelperDependenciesTest} expands. Kept apart from the test, which references all of
 * them, because a nested helper needs its enclosing class and so everything that class uses.
 */
final class HelperDependenciesFixtures {
  private HelperDependenciesFixtures() {}

  /** A helper whose static initializer depends on an anonymous subclass, as JDBCDecorator does. */
  static class Helper {
    static final Base LATCH = new Base() {};

    Object used() {
      return new Used();
    }
  }

  abstract static class Base {}

  static class Used {}

  static class Unrelated {}
}
