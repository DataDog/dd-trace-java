package datadog.trace.common.writer.ddintake;

import static datadog.trace.api.ConfigDefaults.DEFAULT_INTAKE_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_INTAKE_VERSION;
import static datadog.trace.api.intake.TrackType.NOOP;

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

public class DDIntakeApi implements RemoteApi {

  private static final String DD_API_KEY_HEADER = "dd-api-key";
  private static final Logger log = LoggerFactory.getLogger(DDIntakeApi.class);

  private final IOLogger ioLogger = new IOLogger(log);

  public static DDIntakeApiBuilder builder() {
    return new DDIntakeApiBuilder();
  }

  public static class DDIntakeApiBuilder {
    String site = Config.get().getSite();
    String apiVersion = DEFAULT_INTAKE_VERSION;
    TrackType trackType = TrackType.NOOP;
    long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_INTAKE_TIMEOUT);

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

    public DDIntakeApi build() {
      final String trackName = (trackType != null ? trackType.name() : NOOP.name()).toLowerCase();
      final HttpUrl intakeUrl =
          HttpUrl.get(
              "https://" + trackName + "-intake." + site + "/api/" + apiVersion + "/" + trackName);

      final OkHttpClient client = OkHttpUtils.buildHttpClient(intakeUrl, timeoutMillis);
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

      try (final okhttp3.Response response = httpClient.newCall(request).execute()) {
        if (response.code() != 200) {
          return Response.failed(response.code());
        }
        return Response.success(response.code());
      }

    } catch (final IOException e) {
      countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, e);
      return Response.failed(e);
    }
  }

  private void countAndLogFailedSend(
      int traceCount, int sizeInBytes, final okhttp3.Response response, final IOException outer) {}

  @Override
  public void addResponseListener(RemoteResponseListener listener) {}
}
