package datadog.trace.core.datastreams;

import java.lang.reflect.Field;
import org.jctools.queues.MessagePassingQueue;

final class DefaultDataStreamsMonitoringTestBridge {
  private static final Field INBOX_FIELD;
  private static final Field THREAD_FIELD;

  static {
    try {
      INBOX_FIELD = DefaultDataStreamsMonitoring.class.getDeclaredField("inbox");
      INBOX_FIELD.setAccessible(true);
      THREAD_FIELD = DefaultDataStreamsMonitoring.class.getDeclaredField("thread");
      THREAD_FIELD.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
  }

  private DefaultDataStreamsMonitoringTestBridge() {}

  static boolean isInboxEmpty(DefaultDataStreamsMonitoring monitoring) {
    try {
      MessagePassingQueue<?> inbox = (MessagePassingQueue<?>) INBOX_FIELD.get(monitoring);
      return inbox.isEmpty();
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  static Thread.State getThreadState(DefaultDataStreamsMonitoring monitoring) {
    try {
      Thread thread = (Thread) THREAD_FIELD.get(monitoring);
      return thread.getState();
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
}
