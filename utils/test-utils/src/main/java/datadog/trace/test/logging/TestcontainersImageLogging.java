package datadog.trace.test.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import java.io.File;
import java.lang.management.ManagementFactory;

/** Preserves image pull progress independently of test output and retries. */
public final class TestcontainersImageLogging {
  private static final String APPENDER_NAME = "TESTCONTAINERS_IMAGES";
  private static final Object CONFIGURATION_LOCK = new Object();

  private TestcontainersImageLogging() {}

  public static void configure(LoggerContext context) {
    synchronized (CONFIGURATION_LOCK) {
      configureAppender(context);
    }
  }

  private static void configureAppender(LoggerContext context) {
    String directory = System.getProperty("testcontainers.image.log.dir");
    if (directory == null) {
      return;
    }
    Logger imageLogger = context.getLogger("tc");
    if (imageLogger.getAppender(APPENDER_NAME) != null) {
      return;
    }

    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern(
        "%date{yyyy-MM-dd'T'HH:mm:ss.SSS, UTC}Z [%thread] %level %logger - %msg%n%nopex");
    encoder.start();

    FileAppender<ILoggingEvent> appender = new FileAppender<>();
    appender.setContext(context);
    appender.setName(APPENDER_NAME);
    // Separate workers; append when the logging context is reset between specifications.
    String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
    int separator = runtimeName.indexOf('@');
    String processId = separator >= 0 ? runtimeName.substring(0, separator) : runtimeName;
    appender.setFile(new File(directory, "image-pulls-" + processId + ".log").getPath());
    appender.setAppend(true);
    appender.setEncoder(encoder);
    LazyImageAppender lazyAppender = new LazyImageAppender(appender);
    lazyAppender.setContext(context);
    lazyAppender.setName(APPENDER_NAME);
    lazyAppender.addFilter(
        new Filter<ILoggingEvent>() {
          @Override
          public FilterReply decide(ILoggingEvent event) {
            return isImageEvent(event) ? FilterReply.NEUTRAL : FilterReply.DENY;
          }
        });
    lazyAppender.start();
    imageLogger.addAppender(lazyAppender);
    context.getLogger("org.testcontainers.images").addAppender(lazyAppender);
  }

  private static final class LazyImageAppender extends AppenderBase<ILoggingEvent> {
    private final FileAppender<ILoggingEvent> delegate;

    private LazyImageAppender(FileAppender<ILoggingEvent> delegate) {
      this.delegate = delegate;
    }

    @Override
    protected void append(ILoggingEvent event) {
      // AppenderBase serializes accepted events; only the first one opens the file.
      if (!delegate.isStarted()) {
        delegate.start();
      }
      delegate.doAppend(event);
    }

    @Override
    public synchronized void stop() {
      delegate.stop();
      super.stop();
    }
  }

  static boolean isImageEvent(ILoggingEvent event) {
    String logger = event.getLoggerName();
    String message = event.getMessage();
    // Exclude container output, Docker commands, authentication diagnostics and exception bodies.
    if (logger.equals("org.testcontainers.images.AbstractImagePullPolicy")) {
      return message.startsWith("Using locally available")
          || message.startsWith("Image ")
          || message.startsWith("Not available locally")
          || message.startsWith("Should pull locally available image:");
    }
    return logger.startsWith("tc.")
        && (message.startsWith("Pulling docker image:")
            || message.equals("Starting to pull image")
            || message.startsWith("Pulling image layers:")
            || message.startsWith("Pull complete.")
            || message.startsWith("Image ") && message.contains("pull took")
            || message.startsWith("Retrying pull for image:")
            || message.startsWith("Docker image pull has not made progress")
            || message.startsWith("Failed to pull image:"));
  }
}
