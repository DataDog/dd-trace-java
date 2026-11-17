package datadog.telemetry.log;

import static datadog.telemetry.api.LogMessageLevel.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.LogMessage;
import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@WithConfig(key = "instrumentation.telemetry.debug", value = "true")
class LogPeriodicActionTest {

  private final LogPeriodicAction periodicAction = new LogPeriodicAction();
  private final TelemetryService telemetryService = mock(TelemetryService.class);

  @BeforeEach
  void setup() {
    LogCollector.get().drain();
  }

  @AfterEach
  void cleanup() {
    LogCollector.get().drain();
  }

  @Test
  void logWithTags() {
    LogCollector.get().addLogMessage("ERROR", "test", null, "tag1:value1,tag2:value2");
    periodicAction.doIteration(telemetryService);

    LogMessage logMessage = captureSingleLogMessage();
    assertEquals(ERROR, logMessage.getLevel());
    assertEquals("test", logMessage.getMessage());
    assertEquals("tag1:value1,tag2:value2", logMessage.getTags());
  }

  @Test
  void logWithDatadogThrowable() {
    Throwable throwable = throwable("exception", stacktrace(frame("datadog.MyClass")));

    LogCollector.get().addLogMessage(ERROR.toString(), "test", throwable);
    periodicAction.doIteration(telemetryService);

    LogMessage logMessage = captureSingleLogMessage();
    assertEquals("test", logMessage.getMessage());
    assertEquals(
        MutableException.class.getCanonicalName() + "\n" + "  at datadog.MyClass.method(file:42)\n",
        logMessage.getStackTrace());
  }

  @Test
  void logWithNonDatadogThrowable() {
    Throwable throwable = throwable("exception", stacktrace(frame("java.MyClass")));

    LogCollector.get().addLogMessage(ERROR.toString(), "test", throwable);
    periodicAction.doIteration(telemetryService);

    LogMessage logMessage = captureSingleLogMessage();
    assertEquals("test", logMessage.getMessage());
    assertEquals(
        MutableException.class.getCanonicalName() + "\n" + "  at java.MyClass.method(file:42)\n",
        logMessage.getStackTrace());
  }

  @Test
  void logWithDatadogThrowableWithoutStacktrace() {
    Throwable throwable = throwable("exception", new StackTraceElement[0]);

    LogCollector.get().addLogMessage(ERROR.toString(), "test", throwable);
    periodicAction.doIteration(telemetryService);

    LogMessage logMessage = captureSingleLogMessage();
    assertEquals("test", logMessage.getMessage());
    assertEquals(MutableException.class.getCanonicalName() + "\n", logMessage.getStackTrace());
  }

  @Test
  void deduplicationOfLogMessagesWithoutException() {
    LogCollector.get().addLogMessage(ERROR.toString(), "test", null);
    LogCollector.get().addLogMessage(ERROR.toString(), "test", null);
    periodicAction.doIteration(telemetryService);

    LogMessage logMessage = captureSingleLogMessage();
    assertEquals("test", logMessage.getMessage());
    assertEquals(2, logMessage.getCount());
  }

  @Test
  void deduplicationOfLogMessagesWithException() {
    Throwable throwable1 = throwable("exception", stacktrace(frame("datadog.MyClass")));
    Throwable throwable2 = throwable("exception", stacktrace(frame("datadog.MyClass")));

    LogCollector.get().addLogMessage(ERROR.toString(), "test", throwable1);
    LogCollector.get().addLogMessage(ERROR.toString(), "test", throwable2);
    periodicAction.doIteration(telemetryService);

    LogMessage logMessage = captureSingleLogMessage();
    assertEquals("test", logMessage.getMessage());
    assertNotNull(logMessage.getStackTrace());
    assertEquals(2, logMessage.getCount());
  }

  @Test
  void stacktraceRedaction() {
    Throwable throwable =
        throwable(
            "exception",
            stacktrace(
                frame(""),
                frame("java.MyClass"),
                frame("mycorp.MyClass"),
                frame("datadog.MyClass"),
                frame("mycorp.MyClass"),
                frame("mycorp.MyClass")));

    LogCollector.get().addLogMessage(ERROR.toString(), "test", throwable);
    periodicAction.doIteration(telemetryService);

    LogMessage logMessage = captureSingleLogMessage();
    assertEquals("test", logMessage.getMessage());
    assertEquals(
        MutableException.class.getCanonicalName()
            + "\n"
            + "  at (redacted)\n"
            + "  at java.MyClass.method(file:42)\n"
            + "  at (redacted)\n"
            + "  at datadog.MyClass.method(file:42)\n"
            + "  at (redacted: 2 frames)\n",
        logMessage.getStackTrace());
  }

  @Test
  void stacktraceWithMultipleFramesAndCommonFrames() {
    Throwable cause =
        throwable(
            "exception 2",
            stacktrace(
                frame("java.MyClass"),
                frame("mycorp.MyClass"),
                frame("datadog.MyClass"),
                frame("mycorp.MyClass"),
                frame("mycorp.MyClass")));
    Throwable throwable =
        throwable(
            "exception",
            stacktrace(
                frame(""),
                frame("datadog.MyClass"),
                frame("mycorp.MyClass"),
                frame("mycorp.MyClass")),
            cause);

    LogCollector.get().addLogMessage(ERROR.toString(), "test", throwable);
    periodicAction.doIteration(telemetryService);

    LogMessage logMessage = captureSingleLogMessage();
    assertEquals("test", logMessage.getMessage());
    assertEquals(
        MutableException.class.getCanonicalName()
            + "\n"
            + "  at (redacted)\n"
            + "  at datadog.MyClass.method(file:42)\n"
            + "  at (redacted: 2 frames)\n"
            + "Caused by: "
            + MutableException.class.getCanonicalName()
            + "\n"
            + "  at java.MyClass.method(file:42)\n"
            + "  at (redacted)\n"
            + "  ... 3 more\n",
        logMessage.getStackTrace());
  }

  @Test
  void stacktraceWithCommonFramesOnly() {
    Throwable innerCause =
        throwable(
            "exception 3",
            stacktrace(
                frame("java.MyClass"),
                frame("mycorp.MyClass"),
                frame("datadog.MyClass"),
                frame("mycorp.MyClass"),
                frame("mycorp.MyClass")));
    Throwable cause =
        throwable(
            "exception 2",
            stacktrace(
                frame("java.MyClass"),
                frame("mycorp.MyClass"),
                frame("datadog.MyClass"),
                frame("mycorp.MyClass"),
                frame("mycorp.MyClass")),
            innerCause);
    Throwable throwable =
        throwable(
            "exception",
            stacktrace(
                frame("java.MyClass"),
                frame("mycorp.MyClass"),
                frame("datadog.MyClass"),
                frame("mycorp.MyClass"),
                frame("mycorp.MyClass")),
            cause);

    LogCollector.get().addLogMessage(ERROR.toString(), "test", throwable);
    periodicAction.doIteration(telemetryService);

    LogMessage logMessage = captureSingleLogMessage();
    assertEquals("test", logMessage.getMessage());
    assertEquals(
        MutableException.class.getCanonicalName()
            + "\n"
            + "  at java.MyClass.method(file:42)\n"
            + "  at (redacted)\n"
            + "  at datadog.MyClass.method(file:42)\n"
            + "  at (redacted: 2 frames)\n"
            + "Caused by: "
            + MutableException.class.getCanonicalName()
            + "\n"
            + "  ... 5 more\n"
            + "Caused by: "
            + MutableException.class.getCanonicalName()
            + "\n"
            + "  ... 5 more\n",
        logMessage.getStackTrace());
  }

  @Test
  void stacktraceWithoutCommonFrames() {
    Throwable cause =
        throwable(
            "exception 2",
            stacktrace(
                frame("java.MyClass"),
                frame("org.datadog.Test"),
                frame("io.DataTest"),
                frame("dd.MainClass")));
    Throwable throwable =
        throwable(
            "exception",
            stacktrace(
                frame("java.MyClass"),
                frame("mycorp.MyClass"),
                frame("datadog.MyClass"),
                frame("mycorp.MyClass"),
                frame("mycorp.MyClass")),
            cause);

    LogCollector.get().addLogMessage(ERROR.toString(), "test", throwable);
    periodicAction.doIteration(telemetryService);

    LogMessage logMessage = captureSingleLogMessage();
    assertEquals("test", logMessage.getMessage());
    assertEquals(
        MutableException.class.getCanonicalName()
            + "\n"
            + "  at java.MyClass.method(file:42)\n"
            + "  at (redacted)\n"
            + "  at datadog.MyClass.method(file:42)\n"
            + "  at (redacted: 2 frames)\n"
            + "Caused by: "
            + MutableException.class.getCanonicalName()
            + "\n"
            + "  at java.MyClass.method(file:42)\n"
            + "  at (redacted: 3 frames)\n",
        logMessage.getStackTrace());
  }

  private LogMessage captureSingleLogMessage() {
    ArgumentCaptor<LogMessage> captor = ArgumentCaptor.forClass(LogMessage.class);
    verify(telemetryService, times(1)).addLogMessage(captor.capture());
    verifyNoMoreInteractions(telemetryService);
    return captor.getValue();
  }

  private static Throwable throwable(String message, StackTraceElement[] stacktrace) {
    MutableException exception = new MutableException(message, null);
    exception.setStackTrace(stacktrace);
    return exception;
  }

  private static Throwable throwable(
      String message, StackTraceElement[] stacktrace, Throwable cause) {
    MutableException exception = new MutableException(message, cause);
    exception.setStackTrace(stacktrace);
    return exception;
  }

  private static StackTraceElement[] stacktrace(StackTraceElement... frames) {
    return frames;
  }

  private static StackTraceElement frame(String className) {
    return new StackTraceElement(className, "method", "file", 42);
  }

  private static final class MutableException extends Exception {
    MutableException(String message, Throwable cause) {
      super(message, cause, true, true);
    }
  }
}
