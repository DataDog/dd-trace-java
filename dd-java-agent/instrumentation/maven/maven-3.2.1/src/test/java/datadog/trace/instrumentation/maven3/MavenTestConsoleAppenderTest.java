package datadog.trace.instrumentation.maven3;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class MavenTestConsoleAppenderTest {
  @Test
  @ResourceLock(Resources.SYSTEM_OUT)
  void loggingBypassesSubsequentConsoleCapture() throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    LoggerContext context = new LoggerContext();
    MavenTestConsoleAppender appender = new MavenTestConsoleAppender();
    try (PrintStream initial = new PrintStream(output, true, "UTF-8");
        PrintStream redirected = new PrintStream(captured, true, "UTF-8")) {
      PatternLayoutEncoder encoder = new PatternLayoutEncoder();
      encoder.setContext(context);
      encoder.setPattern("%msg%n");
      encoder.start();
      appender.setContext(context);
      appender.setEncoder(encoder);

      System.setOut(initial);
      appender.start();
      System.setOut(redirected);

      LoggingEvent event =
          new LoggingEvent(
              getClass().getName(),
              context.getLogger("test"),
              Level.DEBUG,
              "agent log",
              null,
              null);
      event.setMDCPropertyMap(emptyMap());
      appender.doAppend(event);

      assertTrue(output.toString("UTF-8").contains("agent log"));
      assertEquals(0, captured.size(), "Agent logging must not enter Surefire's console capture");

      appender.stop();
      initial.println("output after appender stop");
      assertTrue(output.toString("UTF-8").contains("output after appender stop"));
    } finally {
      System.setOut(original);
      appender.stop();
      context.stop();
    }
  }
}
