package datadog.trace.common.writer;

import datadog.http.client.HttpResponse;
import datadog.trace.relocate.api.IOLogger;
import java.io.IOException;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;

public abstract class RemoteApi {

  protected final IOLogger ioLogger = new IOLogger(getLogger());

  protected long totalTraces = 0;
  protected long receivedTraces = 0;
  protected long sentTraces = 0;
  protected long failedTraces = 0;

  private final boolean compressionEnabled;

  protected RemoteApi(boolean compressionEnabled) {
    this.compressionEnabled = compressionEnabled;
  }

  public boolean isCompressionEnabled() {
    return compressionEnabled;
  }

  protected void countAndLogSuccessfulSend(final int traceCount, final int sizeInBytes) {
    // count the successful traces
    sentTraces += traceCount;

    ioLogger.success(createSendLogMessage(traceCount, sizeInBytes, "Success"));
  }

  protected void countAndLogFailedSend(
      final int traceCount,
      final int sizeInBytes,
      final HttpResponse response,
      final IOException outer) {
    // count the failed traces
    failedTraces += traceCount;
    // these are used to catch and log if there is a failure in debug logging the response body
    String responseBody = "";
    if (response != null) {
      try {
        responseBody = response.bodyAsString();
      } catch (IOException ignored) {
      }
    }
    String sendErrorString =
        createSendLogMessage(
            traceCount, sizeInBytes, responseBody.isEmpty() ? "Error" : responseBody);

    ioLogger.error(sendErrorString, toLoggerResponse(response, responseBody), outer);
  }

  protected static IOLogger.Response toLoggerResponse(HttpResponse response, String body) {
    if (response == null) {
      return null;
    }
    return new IOLogger.Response(response.code(), "", body);
  }

  protected String createSendLogMessage(
      final int traceCount, final int sizeInBytes, final String prefix) {
    String sizeString = sizeInBytes > 1024 ? (sizeInBytes / 1024) + "KB" : sizeInBytes + "B";
    return prefix
        + " while sending "
        + traceCount
        + " (size="
        + sizeString
        + ")"
        + " traces."
        + " Total: "
        + totalTraces
        + ", Received: "
        + receivedTraces
        + ", Sent: "
        + sentTraces
        + ", Failed: "
        + failedTraces
        + ".";
  }

  protected abstract Response sendSerializedTraces(final Payload payload);

  protected abstract Logger getLogger();

  /**
   * Encapsulates an attempted response from the remote location.
   *
   * <p>If communication fails or times out, the Response will NOT be successful and will lack
   * status code, but will have an exception.
   *
   * <p>If an communication occurs, the Response will have a status code and will be marked as
   * success or fail in accordance with the code.
   *
   * <p>NOTE: A successful communication may still contain an exception if there was a problem
   * parsing the response from the Datadog agent.
   */
  public static final class Response {
    /** Factory method for a successful request with a trivial response body */
    public static Response success(final int status) {
      return new Response(true, status, null, null);
    }

    /** Factory method for a successful request with a trivial response body */
    public static Response success(final int status, String response) {
      return new Response(true, status, null, response);
    }

    /** Factory method for a successful request will a malformed response body */
    public static Response success(final int status, final Throwable exception) {
      return new Response(true, status, exception, null);
    }

    /** Factory method for a request that receive an error status in response */
    public static Response failed(final int status) {
      return new Response(false, status, null, null);
    }

    /** Factory method for a request that receive an error status and a trivial response body */
    public static Response failed(final int status, String response) {
      return new Response(false, status, null, response);
    }

    /** Factory method for a failed communication attempt */
    public static Response failed(final Throwable exception) {
      return new Response(false, null, exception, null);
    }

    private final boolean success;
    private final Integer status;
    private final Throwable exception;
    private final String response;

    private Response(
        final boolean success, final Integer status, final Throwable exception, String response) {
      this.success = success;
      this.status = status;
      this.exception = exception;
      this.response = response;
    }

    public boolean success() {
      return success;
    }

    public OptionalInt status() {
      return status == null ? OptionalInt.empty() : OptionalInt.of(status);
    }

    public Optional<Throwable> exception() {
      return Optional.ofNullable(exception);
    }

    public String response() {
      return response;
    }
  }
}
