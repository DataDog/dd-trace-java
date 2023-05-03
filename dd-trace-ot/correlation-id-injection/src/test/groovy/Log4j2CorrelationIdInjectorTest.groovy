import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Configuration
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.layout.PatternLayout

class Log4j2CorrelationIdInjectorTest extends CorrelationIdInjectorTest {
  @Override
  LogJournal buildJournal() {
    final LoggerContext context = LoggerContext.getContext(false)
    final Configuration config = context.getConfiguration()

    TestAppender appender = new TestAppender(PatternLayout.newBuilder().withPattern(logPattern).build())
    appender.start()
    config.addAppender(appender)
    updateLoggers(appender, config)
    return appender
  }

  @Override
  TestLogger buildLogger() {
    def logger = LogManager.getLogger("TestLogger")
    return { message -> logger.error(message) }
  }

  private static void updateLoggers(final Appender appender, final Configuration config) {
    final Level level = null
    final Filter filter = null
    for (final LoggerConfig loggerConfig : config.getLoggers().values()) {
      loggerConfig.addAppender(appender, level, filter)
    }
    config.getRootLogger().addAppender(appender, level, filter)
  }

  static class TestAppender extends AbstractAppender implements CorrelationIdInjectorTest.LogJournal {
    List events
    int read

    protected TestAppender(PatternLayout patternLayout) {
      super("TestAppender", null, patternLayout, false, null)
      events = []
      read = 0
    }

    @Override
    void append(LogEvent event) {
      def log = getLayout().toSerializable(event)
      events << log
    }

    @Override
    String nextLog() {
      if (events.size() <= read) {
        return null
      }
      return events[read++]
    }
  }
}
