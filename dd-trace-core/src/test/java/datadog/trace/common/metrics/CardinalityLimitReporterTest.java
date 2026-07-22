package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CardinalityLimitReporterTest {

  private Logger logger;
  private Level previousLevel;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    logger = (Logger) LoggerFactory.getLogger(CardinalityLimitReporter.class);
    previousLevel = logger.getLevel();
    // WARN (not DEBUG) so RatelimitedLogger takes the rate-limited path rather than logging always.
    logger.setLevel(Level.WARN);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    logger.setLevel(previousLevel);
  }

  @Test
  void reportsNothingWhenNoBlocksRecorded() {
    CardinalityLimitReporter reporter = new CardinalityLimitReporter();

    reporter.reportIfDue();

    assertEquals(0, appender.list.size());
  }

  @Test
  void aggregatesCountsByTagNameIntoOneLine() {
    CardinalityLimitReporter reporter = new CardinalityLimitReporter();
    // Same tag recorded twice across cycles must sum; distinct tags each appear once.
    reporter.record("resource", 5);
    reporter.record("resource", 3);
    reporter.record("peer.service", 2);

    reporter.reportIfDue();

    assertEquals(1, appender.list.size());
    // Iteration order is the Hashtable's bucket order, not sorted -- assert each entry
    // independently.
    String message = appender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("resource=8"), message);
    assertTrue(message.contains("peer.service=2"), message);
    assertTrue(message.contains("tracer_blocked_value"), message);
  }

  @Test
  void rateLimitsRepeatedReportsWithinTheWindow() {
    CardinalityLimitReporter reporter = new CardinalityLimitReporter();
    reporter.record("resource", 1);
    reporter.reportIfDue(); // first call in the window logs immediately

    // A later cycle within the 5-minute window records more but must not emit a second line.
    reporter.record("resource", 4);
    reporter.reportIfDue();

    assertEquals(1, appender.list.size());
  }

  @Test
  void zeroAndNegativeCountsAreIgnored() {
    CardinalityLimitReporter reporter = new CardinalityLimitReporter();
    reporter.record("resource", 0);

    reporter.reportIfDue();

    assertEquals(0, appender.list.size());
  }
}
