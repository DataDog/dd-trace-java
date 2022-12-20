import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class Slf4jCorrelationIdInjectorTest extends CorrelationIdInjectorTest {

  def logger

  @Override
  LogJournal buildJournal() {
    def logCtx = (LoggerContext) LoggerFactory.getILoggerFactory()
    def logger = logCtx.getLogger("TestLogger")
    this.logger = logger
    def testAppender = new TestAppender(logCtx)
    testAppender.start()
    logger.addAppender(testAppender)
    return testAppender
  }

  @Override
  TestLogger buildLogger() {
    Logger logger = LoggerFactory.getLogger("TestLogger")
    return { message -> logger.error(message)}
  }

  class TestAppender extends AppenderBase<ILoggingEvent> implements CorrelationIdInjectorTest.LogJournal {
    List events
    int read
    PatternLayoutEncoder encoder

    TestAppender(LoggerContext logCtx) {
      name = "TestAppender"
      encoder = new PatternLayoutEncoder()
      encoder.setContext(logCtx)
      encoder.setPattern(logPattern)
      events = []
      read = 0
    }

    @Override
    void start() {
      encoder.start()
      super.start()
    }

    @Override
    void append(ILoggingEvent event) {
      def log = this.encoder.getLayout().doLayout(event)
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
