package datadog.trace.agent.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggedStackOverflowCheckTest {
  @Test
  void julBackgroundErrorFailsOnTheTestThread() throws Exception {
    StackOverflowError overflow = new StackOverflowError("background JUL error");
    try (LoggedStackOverflowCheck check = new LoggedStackOverflowCheck()) {
      Thread background =
          new Thread(
              () -> Logger.getLogger(getClass().getName()).log(Level.SEVERE, "caught", overflow));
      background.start();
      background.join();

      assertSame(overflow, assertThrows(AssertionError.class, check::check).getCause());
    }
  }

  @Test
  void logbackBackgroundErrorFailsOnTheTestThread() throws Exception {
    StackOverflowError overflow = new StackOverflowError("background Logback error");
    try (LoggedStackOverflowCheck check = new LoggedStackOverflowCheck()) {
      Thread background =
          new Thread(() -> LoggerFactory.getLogger(getClass()).error("caught", overflow));
      background.start();
      background.join();

      assertSame(overflow, assertThrows(AssertionError.class, check::check).getCause());
    }
  }

  @Test
  void capturesWrappedAndParameterizedErrorsAndRetainsTheFirst() {
    StackOverflowError first = new StackOverflowError("first");
    try (LoggedStackOverflowCheck check = new LoggedStackOverflowCheck()) {
      LoggerFactory.getLogger(getClass())
          .error("caught in {}", "handler", new RuntimeException(first));
      Logger.getLogger(getClass().getName())
          .log(Level.SEVERE, "caught", new StackOverflowError("second"));

      assertSame(first, assertThrows(AssertionError.class, check::check).getCause());
    }
  }

  @Test
  void ignoresOrdinaryErrorsAndMessagesMentioningStackOverflow() {
    RuntimeException first = new RuntimeException();
    RuntimeException second = new RuntimeException(first);
    first.initCause(second);
    try (LoggedStackOverflowCheck check = new LoggedStackOverflowCheck()) {
      Logger.getLogger(getClass().getName()).log(Level.SEVERE, "StackOverflowError", first);
      LoggerFactory.getLogger(getClass()).error("java.lang.StackOverflowError");

      assertDoesNotThrow(check::check);
    }
  }

  @Test
  void closeDetachesBothLoggingHooksAndNextTestStartsClean() {
    LoggedStackOverflowCheck closed = new LoggedStackOverflowCheck();
    closed.close();
    StackOverflowError overflow = new StackOverflowError("after close");
    Logger.getLogger(getClass().getName()).log(Level.SEVERE, "caught", overflow);
    LoggerFactory.getLogger(getClass()).error("caught", overflow);
    assertDoesNotThrow(closed::check);

    try (LoggedStackOverflowCheck next = new LoggedStackOverflowCheck()) {
      assertDoesNotThrow(next::check);
    }
  }
}
