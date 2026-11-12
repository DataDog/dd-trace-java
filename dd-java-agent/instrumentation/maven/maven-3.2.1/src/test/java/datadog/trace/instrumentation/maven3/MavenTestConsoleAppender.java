package datadog.trace.instrumentation.maven3;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import java.io.OutputStream;
import java.io.PrintStream;

/** Keeps agent logging out of the console capture installed by in-process Surefire tests. */
public final class MavenTestConsoleAppender extends ConsoleAppender<ILoggingEvent> {
  @Override
  public void start() {
    super.start();
    // ConsoleAppender normally resolves System.out on every write. Surefire replaces it with a
    // stream that loads classes, inverting the appender and instrumented classloader lock order.
    PrintStream console = System.out;
    setOutputStream(
        new OutputStream() {
          @Override
          public void write(int value) {
            console.write(value);
          }

          @Override
          public void write(byte[] bytes, int offset, int length) {
            console.write(bytes, offset, length);
          }

          @Override
          public void flush() {
            console.flush();
          }

          @Override
          public void close() {
            // Logback owns this wrapper, but the test worker owns the console.
            flush();
          }
        });
  }
}
