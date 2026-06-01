import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

class Log4j2CorrelationIdInjectorTest extends CorrelationIdInjectorTest {

  @Override
  LogJournal buildJournal() {
    LoggerContext context = LoggerContext.getContext(false);
    Configuration config = context.getConfiguration();

    TestAppender appender =
        new TestAppender(PatternLayout.newBuilder().withPattern(LOG_PATTERN).build());
    appender.start();
    config.addAppender(appender);
    updateLoggers(appender, config);
    return appender;
  }

  @Override
  TestLogger buildLogger() {
    Logger logger = LogManager.getLogger("TestLogger");
    return message -> logger.error(message);
  }

  private static void updateLoggers(Appender appender, Configuration config) {
    Level level = null;
    Filter filter = null;
    for (LoggerConfig loggerConfig : config.getLoggers().values()) {
      loggerConfig.addAppender(appender, level, filter);
    }
    config.getRootLogger().addAppender(appender, level, filter);
  }

  static class TestAppender extends AbstractAppender
      implements CorrelationIdInjectorTest.LogJournal {
    List<String> events;
    int read;

    protected TestAppender(PatternLayout patternLayout) {
      super("TestAppender", null, patternLayout, false, null);
      events = new ArrayList<>();
      read = 0;
    }

    @Override
    public void append(LogEvent event) {
      String log = ((PatternLayout) getLayout()).toSerializable(event);
      events.add(log);
    }

    @Override
    public String nextLog() {
      if (events.size() <= read) {
        return null;
      }
      return events.get(read++);
    }
  }
}
