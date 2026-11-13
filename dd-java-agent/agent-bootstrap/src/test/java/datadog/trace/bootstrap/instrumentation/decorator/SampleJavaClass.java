package datadog.trace.bootstrap.instrumentation.decorator;

/**
 * Provides a top-level anonymous class ({@code SampleJavaClass$1}) for {@link BaseDecoratorTest}'s
 * span name tests.
 */
public class SampleJavaClass {
  public static Class anonymousClass =
      new Runnable() {

        @Override
        public void run() {}
      }.getClass();
}
