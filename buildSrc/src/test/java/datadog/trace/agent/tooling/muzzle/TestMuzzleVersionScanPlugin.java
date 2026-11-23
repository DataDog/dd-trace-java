package datadog.trace.agent.tooling.muzzle;

/** Captures arguments from the isolated scanner for MuzzleActionTest. */
public final class TestMuzzleVersionScanPlugin {
  public static Object[] arguments;

  public static void assertInstrumentationMuzzled(
      ClassLoader instrumentation, ClassLoader application, boolean assertPass, String directive)
      throws Exception {
    arguments = new Object[] {instrumentation, application, assertPass, directive};
    if ("otel".equals(directive)) {
      instrumentation
          .loadClass(TestMuzzleVersionScanPlugin.class.getName() + "$OtelHelper")
          .getMethod("instrument")
          .invoke(null);
    }
    if ("fail".equals(directive)) {
      throw new IllegalStateException("scanner failure");
    }
    if ("error".equals(directive)) {
      throw new AssertionError("scanner failure");
    }
  }

  public static final class OtelInstrument {}

  public static final class OtelHelper {
    public static Object instrument() {
      return new OtelInstrument();
    }
  }
}
