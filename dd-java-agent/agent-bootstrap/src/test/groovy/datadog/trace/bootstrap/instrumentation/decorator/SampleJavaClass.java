package datadog.trace.bootstrap.instrumentation.decorator;

/**
 * Used by {@link BaseDecoratorTest}. Groovy with Java 10+ doesn't seem to treat it properly as an
 * anonymous class, so use a Java class instead.
 */
public class SampleJavaClass {
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("DM_NEW_FOR_GETCLASS")
  public static Class anonymousClass =
      new Runnable() {

        @Override
        public void run() {}
      }.getClass();
}
