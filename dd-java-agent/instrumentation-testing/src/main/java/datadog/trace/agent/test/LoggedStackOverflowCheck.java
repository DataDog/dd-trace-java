package datadog.trace.agent.test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;

/** Captures logged stack overflows so framework exception handling cannot silently pass a test. */
public final class LoggedStackOverflowCheck implements AutoCloseable {
  private final AtomicReference<StackOverflowError> firstError = new AtomicReference<>();
  private final Logger julRoot = Logger.getLogger("");
  private final Handler julHandler =
      new Handler() {
        @Override
        public void publish(LogRecord record) {
          capture(record.getThrown());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };
  private final Runnable detachLogback;

  public LoggedStackOverflowCheck() {
    Object factory = LoggerFactory.getILoggerFactory();
    // Some instrumentation suites deliberately exclude Logback from their runtime classpath.
    detachLogback =
        factory.getClass().getName().equals("ch.qos.logback.classic.LoggerContext")
            ? LogbackCapture.install(factory, this)
            : () -> {};
    julRoot.addHandler(julHandler);
  }

  private void capture(Throwable error) {
    // Do not format a stack trace or log from the thread whose stack is exhausted. Bound traversal
    // because frameworks may wrap the error, and Throwable cause chains can contain cycles.
    for (int depth = 0; error != null && depth < 16; depth++) {
      if (error instanceof StackOverflowError) {
        firstError.compareAndSet(null, (StackOverflowError) error);
        return;
      }
      error = error.getCause();
    }
  }

  public void check() {
    StackOverflowError error = firstError.get();
    if (error != null) {
      throw new AssertionError("A StackOverflowError was logged during the test", error);
    }
  }

  @Override
  public void close() {
    julRoot.removeHandler(julHandler);
    detachLogback.run();
  }

  private static final class LogbackCapture extends ch.qos.logback.classic.turbo.TurboFilter {
    private final LoggedStackOverflowCheck check;

    private LogbackCapture(LoggedStackOverflowCheck check) {
      this.check = check;
    }

    static Runnable install(Object factory, LoggedStackOverflowCheck check) {
      ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) factory;
      LogbackCapture filter = new LogbackCapture(check);
      filter.start();
      context.addTurboFilter(filter);
      return () -> {
        context.getTurboFilterList().remove(filter);
        filter.stop();
      };
    }

    @Override
    public ch.qos.logback.core.spi.FilterReply decide(
        org.slf4j.Marker marker,
        ch.qos.logback.classic.Logger logger,
        ch.qos.logback.classic.Level level,
        String format,
        Object[] params,
        Throwable throwable) {
      check.capture(throwable);
      // Logback can extract the trailing throwable from SLF4J arguments after this callback.
      if (params != null && params.length > 0) {
        Object last = params[params.length - 1];
        if (last instanceof Throwable) {
          check.capture((Throwable) last);
        }
      }
      return ch.qos.logback.core.spi.FilterReply.NEUTRAL;
    }
  }
}
