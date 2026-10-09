package datadog.trace.test.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import java.io.File;
import java.lang.management.ManagementFactory;

/** Preserves image pull progress independently of test output and retries. */
public final class TestcontainersImageLogging {
  private static final String APPENDER_NAME = "TESTCONTAINERS_IMAGES";

  private TestcontainersImageLogging() {}

  public static synchronized void configure(LoggerContext context) {
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
    String processId = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    appender.setFile(new File(directory, "image-pulls-" + processId + ".log").getPath());
    appender.setAppend(true);
    appender.setEncoder(encoder);
    appender.addFilter(
        new Filter<ILoggingEvent>() {
          @Override
          public FilterReply decide(ILoggingEvent event) {
            return isImageEvent(event) ? FilterReply.NEUTRAL : FilterReply.DENY;
          }
        });
    appender.start();
    imageLogger.addAppender(appender);
    context.getLogger("org.testcontainers.images").addAppender(appender);
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
