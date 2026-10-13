package datadog.trace.core.otlp.common;

import static datadog.communication.http.OkHttpUtils.buildHttpClient;
import static datadog.communication.http.OkHttpUtils.isPlainHttp;

import datadog.communication.http.HttpRetryPolicy;
import datadog.logging.RatelimitedLogger;
import datadog.trace.api.config.OtlpConfig.Compression;
import datadog.trace.common.writer.RemoteApi;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends chunks of OTLP data over HTTP. */
public final class OtlpHttpSender implements OtlpSender {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtlpHttpSender.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

  private final HttpRetryPolicy.Factory retryPolicy =
      new HttpRetryPolicy.Factory(5, 100, 2.0, true);

  private final HttpUrl url;
  private final Map<String, String> headers;
  private final boolean gzip;

  private final OkHttpClient client;

  public OtlpHttpSender(
      String endpoint,
      String signalPath,
      Map<String, String> headers,
      int timeoutMillis,
      Compression compression) {

    String unixDomainSocketPath;
    if (endpoint.startsWith("unix://")) {
      unixDomainSocketPath = endpoint.substring(7);
      this.url = HttpUrl.get("http://localhost:4318" + signalPath);
    } else {
      unixDomainSocketPath = null;
      this.url = HttpUrl.get(endpoint); // HTTP endpoint already includes signal path
    }

    this.headers = headers;
    this.gzip = compression == Compression.GZIP;

    this.client = buildHttpClient(isPlainHttp(url), unixDomainSocketPath, null, timeoutMillis);
  }

  public HttpUrl url() {
    return url;
  }

  @Override
  public RemoteApi.Response send(OtlpPayload payload) {
    return OtlpSenderSupport.send(client, retryPolicy, makeRequest(payload), RATELIMITED_LOGGER);
  }

  @Override
  public void shutdown() {
    client.connectionPool().evictAll();
  }

  private Request makeRequest(OtlpPayload payload) {
    Request.Builder requestBuilder = new Request.Builder().url(url);
    if (gzip) {
      requestBuilder.header("Content-Encoding", "gzip").header("Transfer-Encoding", "chunked");
    }

    // add configured headers to the request
    headers.forEach(requestBuilder::addHeader);

    return requestBuilder.post(new OtlpHttpRequestBody(payload, gzip)).build();
  }
}
