package datadog.trace.common.writer.ddintake;

import static datadog.trace.api.intake.TrackType.NOOP;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_TIMEOUT;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_VERSION;

import datadog.communication.http.OkHttpUtils;
import datadog.communication.http.RetryPolicy;
import datadog.trace.api.Config;
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

/** The API pointing to a DD Intake endpoint */
public class DDIntakeApi extends RemoteApi {

  private static final String DD_API_KEY_HEADER = "dd-api-key";

  public static DDIntakeApiBuilder builder() {
    return new DDIntakeApiBuilder();
  }

  public static class DDIntakeApiBuilder {
    private String site = Config.get().getSite();
    private String apiVersion = DEFAULT_INTAKE_VERSION;
    private TrackType trackType = TrackType.NOOP;
    private long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_INTAKE_TIMEOUT);

    HttpUrl hostUrl = null;
    OkHttpClient httpClient = null;
    RetryPolicy retryPolicy = null;

    private String apiKey;

    public DDIntakeApiBuilder trackType(final TrackType trackType) {
      this.trackType = trackType;
      return this;
    }

    public DDIntakeApiBuilder site(final String site) {
      this.site = site;
      return this;
    }

    public DDIntakeApiBuilder apiVersion(final String apiVersion) {
      this.apiVersion = apiVersion;
      return this;
    }

    public DDIntakeApiBuilder apiKey(final String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public DDIntakeApiBuilder timeoutMillis(final long timeoutMillis) {
      this.timeoutMillis = timeoutMillis;
      return this;
    }

    public DDIntakeApiBuilder hostUrl(final HttpUrl hostUrl) {
      this.hostUrl = hostUrl;
      return this;
    }

    public DDIntakeApiBuilder retryPolicy(final RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    DDIntakeApiBuilder httpClient(final OkHttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public DDIntakeApi build() {
      assert apiKey != null;
      final String trackName = (trackType != null ? trackType.name() : NOOP.name()).toLowerCase();
      if (null == hostUrl) {
        hostUrl = HttpUrl.get(String.format("https://%s-intake.%s", trackName, site));
      }
      final HttpUrl intakeUrl = hostUrl.resolve(String.format("/api/%s/%s", apiVersion, trackName));
      final OkHttpClient client =
          (httpClient != null) ? httpClient : OkHttpUtils.buildHttpClient(intakeUrl, timeoutMillis);

      if (null == retryPolicy) {
        retryPolicy = RetryPolicy.builder().withMaxRetry(5).withBackoff(100).build();
      }

      return new DDIntakeApi(client, intakeUrl, apiKey, retryPolicy);
    }
  }

  private final OkHttpClient httpClient;
  private final HttpUrl intakeUrl;
  private final String apiKey;
  private final RetryPolicy retryPolicy;

  private DDIntakeApi(
      OkHttpClient httpClient, HttpUrl intakeUrl, String apiKey, RetryPolicy retryPolicy) {
    this.httpClient = httpClient;
    this.intakeUrl = intakeUrl;
    this.apiKey = apiKey;
    this.retryPolicy = retryPolicy;
  }

  @Override
  public Response sendSerializedTraces(Payload payload) {
    final int sizeInBytes = payload.sizeInBytes();
    boolean shouldRetry;
    int retry = 1;

    try {
      final Request request =
          new Request.Builder()
              .url(intakeUrl)
              .addHeader(DD_API_KEY_HEADER, apiKey)
              .post(payload.toRequest())
              .build();
      this.totalTraces += payload.traceCount();
      this.receivedTraces += payload.traceCount();

      int httpCode = 0;
      IOException lastException = null;
      okhttp3.Response lastResponse = null;
      while (true) {
        // Exponential backoff retry when http code >= 500 or ConnectException is thrown.
        try (final okhttp3.Response response = httpClient.newCall(request).execute()) {
          httpCode = response.code();
          shouldRetry = httpCode >= 500 && retryPolicy.shouldRetry(retry);
          if (!shouldRetry && httpCode >= 400) {
            lastResponse = response;
          }
        } catch (ConnectException ex) {
          shouldRetry = retryPolicy.shouldRetry(retry);
          lastException = ex;
        }

        if (shouldRetry) {
          long backoffMs = retryPolicy.backoff(retry);
          try {
            Thread.sleep(backoffMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
          retry++;
        } else {
          if (httpCode < 200 || httpCode >= 300) {
            countAndLogFailedSend(payload.traceCount(), sizeInBytes, lastResponse, null);
            return Response.failed(httpCode);
          } else if (lastException != null) {
            throw lastException;
          }
          countAndLogSuccessfulSend(payload.traceCount(), sizeInBytes);
          return Response.success(httpCode);
        }
      }
    } catch (final IOException e) {
      countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, e);
      return Response.failed(e);
    }
  }

  @Override
  public void addResponseListener(RemoteResponseListener listener) {}

}
