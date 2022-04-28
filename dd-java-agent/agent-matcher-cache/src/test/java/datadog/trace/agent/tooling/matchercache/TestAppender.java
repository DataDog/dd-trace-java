package datadog.trace.agent.tooling.matchercache;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestAppender extends AppenderBase<ILoggingEvent> {

  public static TestAppender installTestAppender() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    TestAppender testAppender = new TestAppender();
    logger.addAppender(testAppender);
    testAppender.start();
    return testAppender;
  }

  private final Collection<ILoggingEvent> events = new ArrayList<>();

  @Override
  protected void append(ILoggingEvent evt) {
    events.add(evt);
  }

  public void reset() {
    events.clear();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (ILoggingEvent evt : events) {
      sb.append(evt).append('\n');
    }
    return sb.toString();
  }
}
