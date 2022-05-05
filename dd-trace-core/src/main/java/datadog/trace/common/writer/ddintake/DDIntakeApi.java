package datadog.trace.common.writer.ddintake;

import static datadog.trace.api.intake.TrackType.NOOP;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_TIMEOUT;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_VERSION;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.relocate.api.IOLogger;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The API pointing to a DD Intake endpoint */
public class DDIntakeApi implements RemoteApi {

  private static final String DD_API_KEY_HEADER = "dd-api-key";
  private static final Logger log = LoggerFactory.getLogger(DDIntakeApi.class);
  private final IOLogger ioLogger = new IOLogger(log);

  private long totalTraces = 0;
  private long receivedTraces = 0;
  private long sentTraces = 0;
  private long failedTraces = 0;

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

    DDIntakeApiBuilder hostUrl(final HttpUrl hostUrl) {
      this.hostUrl = hostUrl;
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
      return new DDIntakeApi(client, intakeUrl, apiKey);
    }
  }

  private final OkHttpClient httpClient;
  private final HttpUrl intakeUrl;
  private final String apiKey;

  private DDIntakeApi(OkHttpClient httpClient, HttpUrl intakeUrl, String apiKey) {
    this.httpClient = httpClient;
    this.intakeUrl = intakeUrl;
    this.apiKey = apiKey;
  }

  @Override
  public Response sendSerializedTraces(Payload payload) {
    final int sizeInBytes = payload.sizeInBytes();
    try {
      final Request request =
          new Request.Builder()
              .url(intakeUrl)
              .addHeader(DD_API_KEY_HEADER, apiKey)
              .post(payload.toRequest())
              .build();
      this.totalTraces += payload.traceCount();
      this.receivedTraces += payload.traceCount();
      try (final okhttp3.Response response = httpClient.newCall(request).execute()) {
        if (response.code() != 200) {
          countAndLogFailedSend(payload.traceCount(), sizeInBytes, response, null);
          return Response.failed(response.code());
        }
        countAndLogSuccessfulSend(payload.traceCount(), sizeInBytes);
        return Response.success(response.code());
      }

    } catch (final IOException e) {
      countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, e);
      return Response.failed(e);
    }
  }

  private void countAndLogSuccessfulSend(final int traceCount, final int sizeInBytes) {
    // count the successful traces
    this.sentTraces += traceCount;

    ioLogger.success(createSendLogMessage(traceCount, sizeInBytes, "Success"));
  }

  private void countAndLogFailedSend(
      int traceCount, int sizeInBytes, final okhttp3.Response response, final IOException outer) {
    // count the failed traces
    this.failedTraces += traceCount;
    // these are used to catch and log if there is a failure in debug logging the response body
    String intakeError = getResponseBody(response);
    String sendErrorString =
        createSendLogMessage(
            traceCount, sizeInBytes, intakeError.isEmpty() ? "Error" : intakeError);

    ioLogger.error(sendErrorString, toLoggerResponse(response, intakeError), outer);
  }

  private static IOLogger.Response toLoggerResponse(okhttp3.Response response, String body) {
    if (response == null) {
      return null;
    }
    return new IOLogger.Response(response.code(), response.message(), body);
  }

  private static String getResponseBody(okhttp3.Response response) {
    if (response != null) {
      try {
        return response.body().string().trim();
      } catch (NullPointerException | IOException ignored) {
      }
    }
    return "";
  }

  private String createSendLogMessage(
      final int traceCount, final int sizeInBytes, final String prefix) {
    String sizeString = sizeInBytes > 1024 ? (sizeInBytes / 1024) + "KB" : sizeInBytes + "B";
    return prefix
        + " while sending "
        + traceCount
        + " (size="
        + sizeString
        + ")"
        + " traces to the DD Intake."
        + " Total: "
        + this.totalTraces
        + ", Received: "
        + this.receivedTraces
        + ", Sent: "
        + this.sentTraces
        + ", Failed: "
        + this.failedTraces
        + ".";
  }

  @Override
  public void addResponseListener(RemoteResponseListener listener) {}
}
