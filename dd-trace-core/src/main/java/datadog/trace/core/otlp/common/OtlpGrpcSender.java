package datadog.trace.core.otlp.common;

import static datadog.communication.http.OkHttpUtils.buildHttp2Client;
import static datadog.communication.http.OkHttpUtils.isPlainHttp;
import static datadog.communication.http.OkHttpUtils.sendWithRetries;
import static datadog.trace.common.writer.RemoteApi.Response.failed;
import static datadog.trace.common.writer.RemoteApi.Response.success;

import datadog.communication.http.HttpRetryPolicy;
import datadog.logging.RatelimitedLogger;
import datadog.trace.api.config.OtlpConfig.Compression;
import datadog.trace.common.writer.RemoteApi;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends chunks of OTLP data over GRPC. */
public final class OtlpGrpcSender implements OtlpSender {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtlpGrpcSender.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

  private static final int GRPC_OK = 0;
  private static final int GRPC_UNKNOWN = 2;
  private static final String GRPC_CONTENT_TYPE = "application/grpc";

  private final HttpRetryPolicy.Factory retryPolicy =
      new HttpRetryPolicy.Factory(5, 100, 2.0, true);

  private final HttpUrl url;
  private final Map<String, String> headers;
  private final boolean gzip;

  private final OkHttpClient client;

  public OtlpGrpcSender(
      String endpoint,
      String signalPath,
      Map<String, String> headers,
      int timeoutMillis,
      Compression compression) {

    String unixDomainSocketPath;
    if (endpoint.startsWith("unix://")) {
      unixDomainSocketPath = endpoint.substring(7);
      this.url = HttpUrl.get("http://localhost:4317" + signalPath);
    } else {
      unixDomainSocketPath = null;
      this.url = HttpUrl.get(endpoint + signalPath); // GRPC endpoint does not include signal path
    }

    this.headers = headers;
    this.gzip = compression == Compression.GZIP;

    this.client = buildHttp2Client(isPlainHttp(url), unixDomainSocketPath, null, timeoutMillis);
  }

  @Override
  public RemoteApi.Response send(OtlpPayload payload) {
    Request request = makeRequest(payload);
    try (okhttp3.Response response = sendWithRetries(client, retryPolicy, request)) {
      if (!response.isSuccessful()) {
        RATELIMITED_LOGGER.warn(
            "OTLP export to {} failed with status {}: {}",
            request.url(),
            response.code(),
            response.message());
        return failed(response.code());
      }
      IOException grpcError = grpcError(response);
      if (grpcError != null) {
        RATELIMITED_LOGGER.warn(
            "OTLP export to {} failed with {}", request.url(), grpcError.getMessage());
        return failed(grpcError);
      }
      return success(response.code());
    } catch (IOException e) {
      RATELIMITED_LOGGER.warn(
          "OTLP export to {} failed with exception: {}", request.url(), e.toString());
      return failed(e);
    }
  }

  @Override
  public void shutdown() {
    client.connectionPool().evictAll();
  }

  // only used by tests
  public HttpUrl url() {
    return url;
  }

  private Request makeRequest(OtlpPayload payload) {
    Request.Builder requestBuilder = new Request.Builder().url(url);
    if (gzip) {
      requestBuilder.header("grpc-encoding", "gzip");
    }

    // add configured headers to the request
    headers.forEach(requestBuilder::addHeader);

    return requestBuilder.post(new OtlpGrpcRequestBody(payload, gzip)).build();
  }

  /**
   * Returns the error of a 2xx gRPC response, e.g. {@code gRPC status 14: unavailable}, or null if
   * the call succeeded.
   *
   * <p>Only header statuses ("Trailers-Only" responses) are read: OkHttp 3.12 can't read trailers.
   * Without a status header, a gRPC content type is assumed OK; other content types fail as {@code
   * UNKNOWN}.
   */
  @Nullable
  private static IOException grpcError(okhttp3.Response response) {
    String status = response.header("grpc-status");
    if (status == null) {
      String contentType = response.header("Content-Type");
      if (isGrpcContentType(contentType)) {
        return null; // status is in the trailers, which we can't read
      }
      return new IOException(
          "gRPC status " + GRPC_UNKNOWN + " (no grpc-status, content-type: " + contentType + ")");
    }
    int code;
    try {
      code = Integer.parseInt(status.trim());
    } catch (NumberFormatException e) {
      code = GRPC_UNKNOWN;
    }
    if (code == GRPC_OK) {
      return null;
    }
    String grpcMessage = response.header("grpc-message");
    return new IOException(
        "gRPC status " + code + (grpcMessage == null ? "" : ": " + decodeGrpcMessage(grpcMessage)));
  }

  /**
   * Whether the content type is {@code application/grpc}, optionally followed by a {@code +format}
   * suffix or parameters. Media types are case-insensitive; {@code application/grpc-web} is a
   * different protocol and doesn't match.
   */
  private static boolean isGrpcContentType(@Nullable String contentType) {
    int length = GRPC_CONTENT_TYPE.length();
    if (contentType == null || !contentType.regionMatches(true, 0, GRPC_CONTENT_TYPE, 0, length)) {
      return false;
    }
    if (contentType.length() == length) {
      return true;
    }
    char next = contentType.charAt(length);
    return next == '+' || next == ';';
  }

  /**
   * Decodes a {@code grpc-message}, which is percent-encoded UTF-8. A literal {@code +} stays as
   * is, and a message with a malformed escape is kept undecoded.
   */
  private static String decodeGrpcMessage(String message) {
    if (message.indexOf('%') == -1) {
      return message;
    }
    try {
      // percent encoding, not form encoding: '+' isn't a space
      return URLDecoder.decode(message.replace("+", "%2B"), "UTF-8");
    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
      return message;
    }
  }
}
