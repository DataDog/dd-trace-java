package com.datadog.openfeature.testlog;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * Test SLF4J provider that captures log events. The module has no SLF4J 2 compatible logging
 * backend, so this one lets tests assert diagnostics. Nothing is enabled or captured until a test
 * calls {@link #enable(String, Level)}, which keeps the other tests as quiet as without a backend.
 */
public final class CapturingServiceProvider implements SLF4JServiceProvider {
  private static final ConcurrentMap<String, Level> LEVELS = new ConcurrentHashMap<>();
  private static final List<Event> EVENTS = new CopyOnWriteArrayList<>();
  private static volatile String failOn;

  /** A captured log event. */
  public static final class Event {
    public final String logger;
    public final Level level;
    public final String message;
    public final Throwable throwable;

    Event(final String logger, final Level level, final String message, final Throwable throwable) {
      this.logger = logger;
      this.level = level;
      this.message = message;
      this.throwable = throwable;
    }

    @Override
    public String toString() {
      return level + " " + logger + ": " + message;
    }
  }

  private final ILoggerFactory loggerFactory = name -> new CapturingLogger(name);
  private final IMarkerFactory markerFactory = new BasicMarkerFactory();
  private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

  /**
   * Enables a level and captures the events of the loggers whose name starts with the prefix. The
   * longest matching prefix decides.
   *
   * @param prefix the logger name prefix.
   * @param level the lowest enabled level, or {@code null} to disable the prefix.
   */
  public static void enable(final String prefix, final Level level) {
    if (level == null) {
      LEVELS.remove(prefix);
    } else {
      LEVELS.put(prefix, level);
    }
  }

  /**
   * Makes the logging calls throw, as a failing logging backend would: an enabled event whose
   * message contains the text is captured, then the logging call throws.
   *
   * @param text the text of the messages that fail, or {@code null} to stop failing.
   */
  public static void failOn(final String text) {
    failOn = text;
  }

  /** Disables every logger, stops the failures and forgets the captured events. */
  public static void reset() {
    LEVELS.clear();
    EVENTS.clear();
    failOn = null;
  }

  /**
   * @return the captured events, oldest first.
   */
  public static List<Event> events() {
    return EVENTS;
  }

  @Override
  public ILoggerFactory getLoggerFactory() {
    return this.loggerFactory;
  }

  @Override
  public IMarkerFactory getMarkerFactory() {
    return this.markerFactory;
  }

  @Override
  public MDCAdapter getMDCAdapter() {
    return this.mdcAdapter;
  }

  @Override
  public String getRequestedApiVersion() {
    return "2.0.99";
  }

  @Override
  public void initialize() {}

  private static final class CapturingLogger extends AbstractLogger {
    private static final long serialVersionUID = 1L;

    CapturingLogger(final String name) {
      this.name = name;
    }

    private boolean isEnabled(final Level level) {
      // Read the entries once: a test can reset the levels while a worker thread is logging.
      String best = null;
      Level threshold = null;
      for (final Map.Entry<String, Level> entry : LEVELS.entrySet()) {
        final String prefix = entry.getKey();
        if (name.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
          best = prefix;
          threshold = entry.getValue();
        }
      }
      return threshold != null && level.toInt() >= threshold.toInt();
    }

    @Override
    public boolean isTraceEnabled() {
      return isEnabled(Level.TRACE);
    }

    @Override
    public boolean isTraceEnabled(final Marker marker) {
      return isTraceEnabled();
    }

    @Override
    public boolean isDebugEnabled() {
      return isEnabled(Level.DEBUG);
    }

    @Override
    public boolean isDebugEnabled(final Marker marker) {
      return isDebugEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
      return isEnabled(Level.INFO);
    }

    @Override
    public boolean isInfoEnabled(final Marker marker) {
      return isInfoEnabled();
    }

    @Override
    public boolean isWarnEnabled() {
      return isEnabled(Level.WARN);
    }

    @Override
    public boolean isWarnEnabled(final Marker marker) {
      return isWarnEnabled();
    }

    @Override
    public boolean isErrorEnabled() {
      return isEnabled(Level.ERROR);
    }

    @Override
    public boolean isErrorEnabled(final Marker marker) {
      return isErrorEnabled();
    }

    @Override
    protected String getFullyQualifiedCallerName() {
      return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(
        final Level level,
        final Marker marker,
        final String messagePattern,
        final Object[] arguments,
        final Throwable throwable) {
      if (isEnabled(level)) {
        final String message = MessageFormatter.arrayFormat(messagePattern, arguments).getMessage();
        EVENTS.add(new Event(this.name, level, message, throwable));
        final String failing = failOn;
        if (failing != null && message.contains(failing)) {
          throw new IllegalStateException("The logging backend failed on: " + message);
        }
      }
    }
  }
}
