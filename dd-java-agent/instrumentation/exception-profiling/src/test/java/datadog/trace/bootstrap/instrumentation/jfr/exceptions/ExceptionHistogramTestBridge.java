package datadog.trace.bootstrap.instrumentation.jfr.exceptions;

import datadog.trace.api.Config;
import java.util.stream.Stream;

/**
 * {@link ExceptionHistogramTest} is loaded by the application classloader (it has to be, in order
 * to be discovered by JUnit). {@link ExceptionHistogram} is loaded by the bootstrap classloader.
 *
 * <p>As the test and the tested class are loaded by different CLs, the test cannot call
 * package-private methods of the tested class (even though the package name is the same).
 *
 * <p>This class is loaded by the bootstrap classloader as well, and since its methods are public,
 * they can be called by the test, and in turn they can call the package-private methods of the
 * tested class, since classloader is the same.
 */
public class ExceptionHistogramTestBridge {

  public static ExceptionHistogram create(final Config config) {
    return new ExceptionHistogram(config);
  }

  public static ExceptionHistogram create(final Config config, Runnable afterEmit) {
    return new ExceptionHistogram(config) {
      @Override
      void emitEvents(Stream<Pair<String, Long>> items) {
        super.emitEvents(items);
        afterEmit.run();
      }
    };
  }

  public static void doEmit(final ExceptionHistogram histogram) {
    histogram.doEmit();
  }

  public static void deregister(final ExceptionHistogram histogram) {
    histogram.deregister();
  }
}
