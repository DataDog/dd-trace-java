package datadog.trace.common.writer.ddintake;

import static datadog.trace.api.intake.TrackType.NOOP;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_TIMEOUT;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_VERSION;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteApi;
import java.io.IOException;
import java.net.ConnectException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The API pointing to a DD Intake endpoint */
public class DDIntakeApi extends RemoteApi {

  private static final String DD_API_KEY_HEADER = "dd-api-key";
  private static final Logger log = LoggerFactory.getLogger(DDIntakeApi.class);

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
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0);

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

    DDIntakeApiBuilder httpClient(final OkHttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public DDIntakeApi build() {
      assert apiKey != null;
      final String trackName =
          (trackType != null ? trackType.name() : NOOP.name()).toLowerCase(Locale.ROOT);
      if (null == hostUrl) {
        hostUrl = HttpUrl.get(String.format("https://%s-intake.%s", trackName, site));
      }
      final HttpUrl intakeUrl = hostUrl.resolve(String.format("/api/%s/%s", apiVersion, trackName));
      final OkHttpClient client =
          (httpClient != null) ? httpClient : OkHttpUtils.buildHttpClient(intakeUrl, timeoutMillis);

      return new DDIntakeApi(client, intakeUrl, apiKey, retryPolicyFactory);
    }
  }

  private final OkHttpClient httpClient;
  private final HttpUrl intakeUrl;
  private final String apiKey;
  private final HttpRetryPolicy.Factory retryPolicyFactory;

  private DDIntakeApi(
      OkHttpClient httpClient,
      HttpUrl intakeUrl,
      String apiKey,
      HttpRetryPolicy.Factory retryPolicyFactory) {
    this.httpClient = httpClient;
    this.intakeUrl = intakeUrl;
    this.apiKey = apiKey;
    this.retryPolicyFactory = retryPolicyFactory;
  }

  @Override
  public Response sendSerializedTraces(Payload payload) {
    final int sizeInBytes = payload.sizeInBytes();

    final Request request =
        new Request.Builder()
            .url(intakeUrl)
            .addHeader(DD_API_KEY_HEADER, apiKey)
            .post(payload.toRequest())
            .build();
    totalTraces += payload.traceCount();
    receivedTraces += payload.traceCount();

    HttpRetryPolicy retryPolicy = retryPolicyFactory.create();
    try (okhttp3.Response response =
        OkHttpUtils.sendWithRetries(httpClient, retryPolicy, request)) {
      if (response.isSuccessful()) {
        countAndLogSuccessfulSend(payload.traceCount(), sizeInBytes);
        return Response.success(response.code());
      } else {
        countAndLogFailedSend(payload.traceCount(), sizeInBytes, response, null);
        return Response.failed(response.code());
      }

    } catch (ConnectException e) {
      countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, null);
      return Response.failed(e);

    } catch (IOException e) {
      countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, e);
      return Response.failed(e);
    }
  }

  @Override
  protected Logger getLogger() {
    return log;
  }
}
