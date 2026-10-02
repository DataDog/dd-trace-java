package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RedefinitionLoggingListenerForkedTest {

  @Test
  void warnsWhenRetransformationBatchFailsWithoutDebugLogging() {
    // AgentInstaller.DEBUG is fixed when the class initializes, so keep it off in this fork
    ((Logger) LoggerFactory.getLogger(AgentInstaller.class)).setLevel(Level.INFO);
    Logger logger =
        (Logger) LoggerFactory.getLogger(AgentInstaller.RedefinitionLoggingListener.class);
    logger.setLevel(Level.INFO);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      List<Class<?>> batch = Arrays.asList(String.class, Integer.class);

      Iterable<? extends List<Class<?>>> retry =
          new AgentInstaller.RedefinitionLoggingListener()
              .onError(
                  0, batch, new UnsupportedOperationException("class redefinition failed"), batch);

      assertFalse(retry.iterator().hasNext());
      assertEquals(1, appender.list.size());
      ILoggingEvent event = appender.list.get(0);
      assertEquals(Level.WARN, event.getLevel());
      assertTrue(event.getFormattedMessage().contains("retransforming 2 classes"));
      assertTrue(event.getFormattedMessage().contains("class redefinition failed"));
    } finally {
      logger.detachAppender(appender);
    }
  }
}
