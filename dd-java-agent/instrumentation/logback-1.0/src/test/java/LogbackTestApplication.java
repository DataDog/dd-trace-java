import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class LogbackTestApplication implements LogbackApplication {
  private final Logger logger = (Logger) LoggerFactory.getLogger("test.logback.intake");
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch delivered = new CountDownLatch(1);
  private Map<String, String> deliveredContext;
  private AsyncAppender async;
  private final List<Map<String, String>> contexts = new ArrayList<>();

  public LogbackTestApplication() {
    logger.setLevel(Level.DEBUG);
    AppenderBase<ILoggingEvent> sink =
        new AppenderBase<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent event) {
            contexts.add(new HashMap<>(event.getMDCPropertyMap()));
          }
        };
    sink.setContext(logger.getLoggerContext());
    sink.start();
    logger.addAppender(sink);
  }

  @Override
  public List<Map<String, String>> getContexts() {
    return contexts;
  }

  @Override
  public void log(String level, String message) {
    switch (level) {
      case "DEBUG":
        logger.debug(message);
        break;
      case "INFO":
        logger.info(message);
        break;
      case "ERROR":
        logger.error(message, new IllegalStateException("failure"));
        break;
      default:
        logger.warn("{}", message);
    }
  }

  @Override
  public void put(String key, String value) {
    MDC.put(key, value);
  }

  @Override
  public void clear() {
    MDC.clear();
  }

  @Override
  public void startAsync() {
    AppenderBase<ILoggingEvent> sink =
        new AppenderBase<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent event) {
            try {
              if (release.await(5, TimeUnit.SECONDS)) {
                deliveredContext = new HashMap<>(event.getMDCPropertyMap());
                delivered.countDown();
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };
    sink.setContext(logger.getLoggerContext());
    sink.start();
    async = new AsyncAppender();
    async.setContext(logger.getLoggerContext());
    async.addAppender(sink);
    async.start();
    logger.addAppender(async);
  }

  @Override
  public Map<String, String> finishAsync() throws InterruptedException {
    release.countDown();
    try {
      if (!delivered.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("AsyncAppender did not deliver");
      }
      return deliveredContext;
    } finally {
      logger.detachAppender(async);
      async.stop();
    }
  }

  @Override
  public void jul() {
    java.util.logging.Logger jul = java.util.logging.Logger.getLogger(logger.getName());
    SLF4JBridgeHandler bridge = new SLF4JBridgeHandler();
    boolean parentHandlers = jul.getUseParentHandlers();
    jul.setUseParentHandlers(false);
    jul.addHandler(bridge);
    try {
      jul.warning("bridged");
    } finally {
      jul.removeHandler(bridge);
      jul.setUseParentHandlers(parentHandlers);
      bridge.close();
    }
  }
}
