package datadog.trace.relocate.api;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/** Logger specialized on logging IO-related activity */
public class IOLogger {
  private boolean logNextSuccess = false;
  private final Logger log;
  private final RatelimitedLogger ratelimitedLogger;

  public IOLogger(final Logger log) {
    this(log, new RatelimitedLogger(log, 5, TimeUnit.MINUTES));
  }

  // Visible for testing
  IOLogger(final Logger log, final RatelimitedLogger ratelimitedLogger) {
    this.log = log;
    this.ratelimitedLogger = ratelimitedLogger;
  }

  /**
   * @return true if actually logged the message, false otherwise
   */
  public boolean success(final String format, final Object... arguments) {
    if (log.isDebugEnabled()) {
      log.debug(EXCLUDE_TELEMETRY, format, arguments);
      return true;
    }

    if (this.logNextSuccess) {
      this.logNextSuccess = false;
      if (log.isInfoEnabled()) {
        log.info(EXCLUDE_TELEMETRY, format, arguments);
        return true;
      }
    }

    return false;
  }

  /**
   * @return true if actually logged the message, false otherwise
   */
  public boolean error(final String message) {
    return error(message, null, null);
  }

  /**
   * @return true if actually logged the message, false otherwise
   */
  public boolean error(final String message, Exception exception) {
    return error(message, null, exception);
  }

  /**
   * @return true if actually logged the message, false otherwise
   */
  public boolean error(final String message, Response response) {
    return error(message, response, null);
  }

  /**
   * @return true if actually logged the message, false otherwise
   */
  public boolean error(final String message, Response response, Exception exception) {
    if (log.isDebugEnabled()) {
      if (response != null) {
        log.debug(
            EXCLUDE_TELEMETRY,
            "{} Status: {}, Response: {}, Body: {}",
            message,
            response.getStatusCode(),
            response.getMessage(),
            response.getBody());
      } else if (exception != null) {
        log.debug(EXCLUDE_TELEMETRY, message, exception);
      } else {
        log.debug(EXCLUDE_TELEMETRY, message);
      }
      return true;
    }
    boolean hasLogged;
    if (response != null) {
      hasLogged =
          ratelimitedLogger.warn(
              EXCLUDE_TELEMETRY,
              "{} Status: {} {}",
              message,
              response.getStatusCode(),
              response.getMessage());
    } else if (exception != null) {
      // NOTE: We do not pass the full exception to warn on purpose. We don't want to
      //       print a full stacktrace unless we're in debug mode
      hasLogged =
          ratelimitedLogger.warn(
              EXCLUDE_TELEMETRY,
              "{} {}: {}",
              message,
              exception.getClass().getName(),
              exception.getMessage());
    } else {
      hasLogged = ratelimitedLogger.warn(message);
    }
    if (hasLogged) {
      this.logNextSuccess = true;
    }

    return hasLogged;
  }

  public static final class Response {
    private final int statusCode;
    private final String message;
    private final String body;

    public Response(int statusCode, String message, String body) {
      this.statusCode = statusCode;
      this.message = message;
      this.body = body;
    }

    public int getStatusCode() {
      return statusCode;
    }

    public String getMessage() {
      return message;
    }

    public String getBody() {
      return body;
    }
  }
}
