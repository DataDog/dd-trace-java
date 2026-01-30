package datadog.trace.common.writer.ddintake;

import static datadog.trace.api.intake.TrackType.NOOP;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_TIMEOUT;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_VERSION;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.HttpUtils;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpResponse;
import datadog.http.client.HttpUrl;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteApi;
import java.io.IOException;
import java.net.ConnectException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The API pointing to a DD Intake endpoint */
public class DDIntakeApi extends RemoteApi {

  private static final Logger log = LoggerFactory.getLogger(DDIntakeApi.class);

  private static final String DD_API_KEY_HEADER = "dd-api-key";
  private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
  private static final String GZIP_CONTENT_TYPE = "gzip";

  public static DDIntakeApiBuilder builder() {
    return new DDIntakeApiBuilder();
  }

  public static class DDIntakeApiBuilder {
    private String site = Config.get().getSite();
    private String apiVersion = DEFAULT_INTAKE_VERSION;
    private TrackType trackType = TrackType.NOOP;
    private long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_INTAKE_TIMEOUT);

    HttpUrl hostUrl = null;
    HttpClient httpClient = null;
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0, true);

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

    public DDIntakeApiBuilder httpClient(final HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public DDIntakeApi build() {
      assert apiKey != null;
      final String trackName =
          (trackType != null ? trackType.name() : NOOP.name()).toLowerCase(Locale.ROOT);
      if (null == hostUrl) {
        hostUrl = HttpUrl.parse(String.format("https://%s-intake.%s", trackName, site));
      }
      final HttpUrl intakeUrl = hostUrl.resolve(String.format("/api/%s/%s", apiVersion, trackName));
      final HttpClient client =
          (httpClient != null) ? httpClient : HttpUtils.buildHttpClient(intakeUrl, timeoutMillis);

      return new DDIntakeApi(trackType, client, intakeUrl, apiKey, retryPolicyFactory);
    }
  }

  private final TelemetryListener telemetryListener;
  private final TrackType trackType;
  private final HttpClient httpClient;
  private final HttpUrl intakeUrl;
  private final String apiKey;
  private final HttpRetryPolicy.Factory retryPolicyFactory;

  private DDIntakeApi(
      TrackType trackType,
      HttpClient httpClient,
      HttpUrl intakeUrl,
      String apiKey,
      HttpRetryPolicy.Factory retryPolicyFactory) {
    super(true);
    this.telemetryListener = new TelemetryListener(trackType.endpoint);
    this.trackType = trackType;
    this.httpClient = httpClient;
    this.intakeUrl = intakeUrl;
    this.apiKey = apiKey;
    this.retryPolicyFactory = retryPolicyFactory;
  }

  @Override
  public Response sendSerializedTraces(Payload payload) {
    final int sizeInBytes = payload.sizeInBytes();

    final HttpRequest request = HttpRequest.newBuilder()
            .url(intakeUrl)
            .addHeader(DD_API_KEY_HEADER, apiKey)
            .addHeader(CONTENT_ENCODING_HEADER, GZIP_CONTENT_TYPE)
            .post(payload.toRequest())
            .listener(telemetryListener)
            .build();
    totalTraces += payload.traceCount();
    receivedTraces += payload.traceCount();

    try (HttpResponse response =
        HttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request)) {
      if (response.isSuccessful()) {
        countAndLogSuccessfulSend(payload.traceCount(), sizeInBytes);
        return Response.success(response.code());
      } else {
        InstrumentationBridge.getMetricCollector()
            .add(CiVisibilityCountMetric.ENDPOINT_PAYLOAD_DROPPED, 1, trackType.endpoint);
        countAndLogFailedSend(payload.traceCount(), sizeInBytes, response, null);
        return Response.failed(response.code());
      }

    } catch (ConnectException e) {
      InstrumentationBridge.getMetricCollector()
          .add(CiVisibilityCountMetric.ENDPOINT_PAYLOAD_DROPPED, 1, trackType.endpoint);
      countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, null);
      return Response.failed(e);

    } catch (IOException e) {
      InstrumentationBridge.getMetricCollector()
          .add(CiVisibilityCountMetric.ENDPOINT_PAYLOAD_DROPPED, 1, trackType.endpoint);
      countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, e);
      return Response.failed(e);
    }
  }

  @Override
  protected Logger getLogger() {
    return log;
  }
}
