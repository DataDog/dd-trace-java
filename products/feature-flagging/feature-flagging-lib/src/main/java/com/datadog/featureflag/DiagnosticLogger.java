package com.datadog.featureflag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/** Isolates diagnostic backend failures from feature flag behavior. */
public final class DiagnosticLogger {
  private final Logger delegate;

  private DiagnosticLogger(Logger delegate) {
    this.delegate = delegate;
  }

  public static DiagnosticLogger getLogger(Class<?> type) {
    Logger logger;
    try {
      logger = LoggerFactory.getLogger(type);
    } catch (Throwable ignored) {
      logger = null;
    }
    return new DiagnosticLogger(logger);
  }

  /** The rate limiter's calls are guarded by its caller, including its own level checks. */
  Logger backend() {
    return delegate == null ? org.slf4j.helpers.NOPLogger.NOP_LOGGER : delegate;
  }

  public boolean isDebugEnabled() {
    try {
      return delegate != null && delegate.isDebugEnabled();
    } catch (Throwable ignored) {
      return false;
    }
  }

  public void debug(String message) {
    try {
      if (delegate != null) {
        delegate.debug(message);
      }
    } catch (Throwable ignored) {
      // A diagnostic must never interrupt evaluation, readiness notification, or teardown.
    }
  }

  public void debug(String message, Object argument) {
    try {
      if (delegate != null) {
        delegate.debug(message, argument);
      }
    } catch (Throwable ignored) {
      // A diagnostic must never interrupt evaluation, readiness notification, or teardown.
    }
  }

  public void debug(String message, Object first, Object second) {
    try {
      if (delegate != null) {
        delegate.debug(message, first, second);
      }
    } catch (Throwable ignored) {
      // A diagnostic must never interrupt evaluation, readiness notification, or teardown.
    }
  }

  public void debug(String message, Object... arguments) {
    try {
      if (delegate != null) {
        delegate.debug(message, arguments);
      }
    } catch (Throwable ignored) {
      // A diagnostic must never interrupt evaluation, readiness notification, or teardown.
    }
  }

  public void warn(String message) {
    try {
      if (delegate != null) {
        delegate.warn(message);
      }
    } catch (Throwable ignored) {
      // A diagnostic must never interrupt evaluation, readiness notification, or teardown.
    }
  }

  public void warn(String message, Object... arguments) {
    try {
      if (delegate != null) {
        delegate.warn(message, arguments);
      }
    } catch (Throwable ignored) {
      // A diagnostic must never interrupt evaluation, readiness notification, or teardown.
    }
  }

  public void error(String message, Throwable error) {
    try {
      if (delegate != null) {
        delegate.error(message, error);
      }
    } catch (Throwable ignored) {
      // A diagnostic must never interrupt teardown.
    }
  }

  public void error(Marker marker, String message, Throwable error) {
    try {
      if (delegate != null) {
        delegate.error(marker, message, error);
      }
    } catch (Throwable ignored) {
      // A diagnostic must never interrupt evaluation, readiness notification, or teardown.
    }
  }
}
