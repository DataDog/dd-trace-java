package datadog.trace.common.writer.ddintake;

import static datadog.trace.api.intake.TrackType.NOOP;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_TIMEOUT;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_VERSION;

import datadog.communication.http.OkHttpUtils;
import datadog.communication.http.RetryPolicy;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.common.writer.RemoteResponseListener;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The API pointing to a DD Intake endpoint */
public class DDEvpProxyApi extends RemoteApi {

  private static final Logger log = LoggerFactory.getLogger(DDEvpProxyApi.class);

  private static final String DD_EVP_SUBDOMAIN_HEADER = "X-Datadog-EVP-Subdomain";

  public static DDEvpProxyApiBuilder builder() {
    return new DDEvpProxyApiBuilder();
  }

  public static class DDEvpProxyApiBuilder {
    private String apiVersion = DEFAULT_INTAKE_VERSION;
    private TrackType trackType = TrackType.NOOP;
    private long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_INTAKE_TIMEOUT);

    HttpUrl agentUrl = null;
    OkHttpClient httpClient = null;
    RetryPolicy retryPolicy = null;
    String evpProxyEndpoint;

    public DDEvpProxyApiBuilder trackType(final TrackType trackType) {
      this.trackType = trackType;
      return this;
    }

    public DDEvpProxyApiBuilder apiVersion(final String apiVersion) {
      this.apiVersion = apiVersion;
      return this;
    }

    public DDEvpProxyApiBuilder evpProxyEndpoint(final String evpProxyEndpoint) {
      this.evpProxyEndpoint = evpProxyEndpoint;
      return this;
    }

    public DDEvpProxyApiBuilder timeoutMillis(final long timeoutMillis) {
      this.timeoutMillis = timeoutMillis;
      return this;
    }

    public DDEvpProxyApiBuilder agentUrl(final HttpUrl agentUrl) {
      this.agentUrl = agentUrl;
      return this;
    }

    public DDEvpProxyApiBuilder retryPolicy(final RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    DDEvpProxyApiBuilder httpClient(final OkHttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public DDEvpProxyApi build() {
      final String trackName = (trackType != null ? trackType.name() : NOOP.name()).toLowerCase();
      final String subdomain = String.format("%s-intake", trackName);

      final HttpUrl evpProxyUrl = agentUrl.resolve(evpProxyEndpoint);
      final HttpUrl proxiedApiUrl =
          evpProxyUrl.resolve(String.format("api/%s/%s", apiVersion, trackName));

      final OkHttpClient client =
          (httpClient != null)
              ? httpClient
              : OkHttpUtils.buildHttpClient(proxiedApiUrl, timeoutMillis);

      if (null == retryPolicy) {
        retryPolicy = RetryPolicy.builder().withMaxRetry(5).withBackoff(100).build();
      }

      log.debug("proxiedApiUrl: " + proxiedApiUrl);
      return new DDEvpProxyApi(client, proxiedApiUrl, subdomain, retryPolicy);
    }
  }

  private final OkHttpClient httpClient;
  private final HttpUrl proxiedApiUrl;
  private final String subdomain;
  private final RetryPolicy retryPolicy;

  private DDEvpProxyApi(
      OkHttpClient httpClient, HttpUrl proxiedApiUrl, String subdomain, RetryPolicy retryPolicy) {
    this.httpClient = httpClient;
    this.proxiedApiUrl = proxiedApiUrl;
    this.subdomain = subdomain;
    this.retryPolicy = retryPolicy;
  }

  @Override
  public Response sendSerializedTraces(Payload payload) {
    final int sizeInBytes = payload.sizeInBytes();
    int retry = 1;

    try {
      final Request request =
          new Request.Builder()
              .url(proxiedApiUrl)
              .addHeader(DD_EVP_SUBDOMAIN_HEADER, subdomain)
              .post(payload.toRequest())
              .build();
      this.totalTraces += payload.traceCount();
      this.receivedTraces += payload.traceCount();

      while (true) {
        // Exponential backoff retry when http code >= 500 or ConnectException is thrown.
        try (final okhttp3.Response response = httpClient.newCall(request).execute()) {
          if (response.isSuccessful()) {
            countAndLogSuccessfulSend(payload.traceCount(), sizeInBytes);
            return Response.success(response.code());
          }
          int httpCode = response.code();
          boolean shouldRetry = httpCode >= 500 && retryPolicy.shouldRetry(retry);
          if (!shouldRetry) {
            countAndLogFailedSend(payload.traceCount(), sizeInBytes, response, null);
            return Response.failed(httpCode);
          }
        } catch (ConnectException ex) {
          boolean shouldRetry = retryPolicy.shouldRetry(retry);
          if (!shouldRetry) {
            countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, null);
            return Response.failed(ex);
          }
        }
        // If we get here, there has been an error and we still have retries left
        long backoffMs = retryPolicy.backoff(retry);
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
        retry++;
      }
    } catch (final IOException e) {
      countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, e);
      return Response.failed(e);
    }
  }

  @Override
  public void addResponseListener(RemoteResponseListener listener) {}

  @Override
  protected Logger getLogger() {
    return log;
  }
}
