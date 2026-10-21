package datadog.trace.api.openfeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Isolates diagnostic backend failures from feature flag behavior.
 *
 * <p>The provider owns this helper so it also works without agent classes or with an older agent.
 */
final class DiagnosticLogger {
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

  public void info(String message, Object argument) {
    try {
      if (delegate != null) {
        delegate.info(message, argument);
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
      // A diagnostic must never interrupt evaluation, readiness notification, or teardown.
    }
  }
}
