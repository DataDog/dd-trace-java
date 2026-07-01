import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Slf4jCorrelationIdInjectorTest extends CorrelationIdInjectorTest {

  ch.qos.logback.classic.Logger logger;

  @Override
  LogJournal buildJournal() {
    LoggerContext logCtx = (LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger log = logCtx.getLogger("TestLogger");
    this.logger = log;
    TestAppender testAppender = new TestAppender(logCtx);
    testAppender.start();
    log.addAppender(testAppender);
    return testAppender;
  }

  @Override
  TestLogger buildLogger() {
    Logger log = LoggerFactory.getLogger("TestLogger");
    return message -> log.error(message);
  }

  class TestAppender extends AppenderBase<ILoggingEvent>
      implements CorrelationIdInjectorTest.LogJournal {
    private final List<String> events;
    int read;
    private final PatternLayoutEncoder encoder;

    TestAppender(LoggerContext logCtx) {
      name = "TestAppender";
      encoder = new PatternLayoutEncoder();
      encoder.setContext(logCtx);
      encoder.setPattern(LOG_PATTERN);
      events = new ArrayList<>();
      read = 0;
    }

    @Override
    public void start() {
      encoder.start();
      super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
      String log = this.encoder.getLayout().doLayout(event);
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
