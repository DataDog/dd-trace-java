package datadog.trace.core.otlp.common;

import static datadog.communication.http.OkHttpUtils.buildHttp2Client;
import static datadog.communication.http.OkHttpUtils.isPlainHttp;
import static datadog.communication.http.OkHttpUtils.sendWithRetries;

import datadog.communication.http.HttpRetryPolicy;
import datadog.logging.RatelimitedLogger;
import datadog.trace.api.config.OtlpConfig.Compression;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends chunks of OTLP data over GRPC. */
public final class OtlpGrpcSender implements OtlpSender {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtlpGrpcSender.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

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
  public void send(OtlpPayload payload) {
    Request request = makeRequest(payload);
    try (Response response = sendWithRetries(client, retryPolicy, request)) {
      if (!response.isSuccessful()) {
        RATELIMITED_LOGGER.warn(
            "OTLP export to {} failed with status {}: {}",
            request.url(),
            response.code(),
            response.message());
      }
    } catch (IOException e) {
      RATELIMITED_LOGGER.warn(
          "OTLP export to {} failed with exception: {}", request.url(), e.toString());
    }
  }

  @Override
  public void shutdown() {
    client.connectionPool().evictAll();
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
}
