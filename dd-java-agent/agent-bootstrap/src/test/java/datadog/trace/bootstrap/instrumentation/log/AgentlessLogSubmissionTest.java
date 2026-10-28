package datadog.trace.bootstrap.instrumentation.log;

import static datadog.trace.bootstrap.instrumentation.log.AgentlessLogSubmission.isLevelEnabled;
import static datadog.trace.bootstrap.instrumentation.log.AgentlessLogSubmission.julLevel;
import static datadog.trace.bootstrap.instrumentation.log.AgentlessLogSubmission.severity;
import static datadog.trace.bootstrap.instrumentation.log.AgentlessLogSubmission.submit;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.logging.intake.LogsIntake;
import datadog.trace.api.logging.intake.LogsWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class AgentlessLogSubmissionTest {

  private final List<Map<String, Object>> messages = new ArrayList<>();

  @BeforeEach
  void registerWriter() {
    LogsIntake.registerWriter(
        new LogsWriter() {
          @Override
          public void log(Map<String, Object> message) {
            messages.add(message);
          }

          @Override
          public void start() {}

          @Override
          public void shutdown() {}
        });
  }

  @AfterEach
  void unregisterWriter() {
    LogsIntake.registerWriter(null);
  }

  @TableTest({
    "scenario    | value | level",
    "OFF         | 2000  | FATAL",
    "JBoss FATAL | 1100  | FATAL",
    "SEVERE      | 1000  | ERROR",
    "WARNING     | 900   | WARN ",
    "INFO        | 800   | INFO ",
    "CONFIG      | 700   | DEBUG",
    "FINE        | 500   | DEBUG",
    "FINER       | 400   | TRACE",
    "FINEST      | 300   | TRACE"
  })
  void mapsJulLevels(int value, String level) {
    assertEquals(level, julLevel(value));
  }

  @TableTest({
    "scenario      | lower | higher",
    "ALL and TRACE | ALL   | TRACE ",
    "TRACE, DEBUG  | trace | DEBUG ",
    "DEBUG, INFO   | DEBUG | INFO  ",
    "INFO, WARN    | INFO  | WARN  ",
    "WARN, ERROR   | WARN  | ERROR ",
    "ERROR, FATAL  | ERROR | FATAL ",
    "FATAL, OFF    | FATAL | OFF   "
  })
  void ordersLevels(String lower, String higher) {
    assertTrue(severity(lower) < severity(higher));
  }

  @Test
  void defaultsToInfo() {
    assertFalse(isLevelEnabled("DEBUG"));
    assertTrue(isLevelEnabled("INFO"));
    assertTrue(isLevelEnabled("unknown"));
  }

  @Test
  void submitsEvent() {
    submit(
        "main",
        "ERROR",
        "logger",
        "message",
        new IllegalStateException("failure"),
        42L,
        singletonMap("custom", "value"));

    assertEquals(1, messages.size());
    Map<String, Object> message = messages.get(0);
    assertEquals("main", message.get("thread"));
    assertEquals("ERROR", message.get("level"));
    assertEquals("logger", message.get("loggerName"));
    assertEquals("message", message.get("message"));
    assertEquals(42L, message.get("timestamp"));
    assertEquals(singletonMap("custom", "value"), message.get("contextMap"));
    Map<?, ?> thrown = (Map<?, ?>) message.get("thrown");
    assertEquals(IllegalStateException.class.getName(), thrown.get("name"));
    assertEquals("failure", thrown.get("message"));
    assertTrue(
        thrown
            .get("extendedStackTrace")
            .toString()
            .startsWith("java.lang.IllegalStateException: failure"));
  }

  @Test
  void usesTheBinaryNameOfTheThrowableClass() {
    Throwable anonymous = new IllegalStateException("failure") {};

    submit("main", "ERROR", "logger", "message", anonymous, 42L, null);

    Map<?, ?> thrown = (Map<?, ?>) messages.get(0).get("thrown");
    assertEquals(anonymous.getClass().getName(), thrown.get("name"));
  }

  @Test
  void stopsRenderingTheStackTraceAtTheMaximumLength() {
    AtomicBoolean causeRendered = new AtomicBoolean();
    Throwable cause =
        new IllegalArgumentException("cause") {
          @Override
          public String toString() {
            causeRendered.set(true);
            return super.toString();
          }
        };
    Throwable thrown = new IllegalStateException("failure", cause);
    StackTraceElement[] frames = new StackTraceElement[1_000];
    Arrays.fill(frames, new StackTraceElement("Class", "method", "Class.java", 1));
    thrown.setStackTrace(frames);

    submit("main", "ERROR", "logger", "message", thrown, 42L, null);

    String stackTrace =
        ((Map<?, ?>) messages.get(0).get("thrown")).get("extendedStackTrace").toString();
    assertEquals(16 * 1_024, stackTrace.length());
    assertTrue(stackTrace.startsWith("java.lang.IllegalStateException: failure"));
    assertFalse(causeRendered.get());
  }

  @Test
  void submitsEventWithoutOptionalFields() {
    submit("main", "INFO", "logger", "message", null, 42L, null);

    assertEquals(1, messages.size());
    assertFalse(messages.get(0).containsKey("thrown"));
    assertTrue(((Map<?, ?>) messages.get(0).get("contextMap")).isEmpty());
  }
}
