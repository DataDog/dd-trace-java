package datadog.trace.common.writer.ddintake;

import static datadog.trace.api.intake.TrackType.NOOP;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_TIMEOUT;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_VERSION;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
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
public class DDEvpProxyApi extends RemoteApi {

  private static final Logger log = LoggerFactory.getLogger(DDEvpProxyApi.class);

  private static final String DD_EVP_SUBDOMAIN_HEADER = "X-Datadog-EVP-Subdomain";
  private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
  private static final String GZIP_CONTENT_TYPE = "gzip";

  public static DDEvpProxyApiBuilder builder() {
    return new DDEvpProxyApiBuilder();
  }

  public static class DDEvpProxyApiBuilder {
    private String apiVersion = DEFAULT_INTAKE_VERSION;
    private TrackType trackType = TrackType.NOOP;
    private long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_INTAKE_TIMEOUT);

    HttpUrl agentUrl = null;
    OkHttpClient httpClient = null;
    String evpProxyEndpoint;
    boolean compressionEnabled;

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

    public DDEvpProxyApiBuilder httpClient(final OkHttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public DDEvpProxyApiBuilder compressionEnabled(boolean compressionEnabled) {
      this.compressionEnabled = compressionEnabled;
      return this;
    }

    public DDEvpProxyApi build() {
      final String trackName =
          (trackType != null ? trackType.name() : NOOP.name()).toLowerCase(Locale.ROOT);
      final String subdomain = String.format("%s-intake", trackName);

      final HttpUrl evpProxyUrl = agentUrl.resolve(evpProxyEndpoint);
      final HttpUrl proxiedApiUrl =
          evpProxyUrl.resolve(String.format("api/%s/%s", apiVersion, trackName));

      final OkHttpClient client =
          (httpClient != null)
              ? httpClient
              : OkHttpUtils.buildHttpClient(proxiedApiUrl, timeoutMillis);

      final HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0);

      log.debug("proxiedApiUrl: {}", proxiedApiUrl);
      return new DDEvpProxyApi(
          client, proxiedApiUrl, subdomain, retryPolicyFactory, compressionEnabled);
    }
  }

  private final OkHttpClient httpClient;
  private final HttpUrl proxiedApiUrl;
  private final String subdomain;
  private final HttpRetryPolicy.Factory retryPolicyFactory;

  private DDEvpProxyApi(
      OkHttpClient httpClient,
      HttpUrl proxiedApiUrl,
      String subdomain,
      HttpRetryPolicy.Factory retryPolicyFactory,
      boolean compressionEnabled) {
    super(compressionEnabled);
    this.httpClient = httpClient;
    this.proxiedApiUrl = proxiedApiUrl;
    this.subdomain = subdomain;
    this.retryPolicyFactory = retryPolicyFactory;
  }

  @Override
  public Response sendSerializedTraces(Payload payload) {
    final int sizeInBytes = payload.sizeInBytes();

    Request.Builder builder =
        new Request.Builder().url(proxiedApiUrl).addHeader(DD_EVP_SUBDOMAIN_HEADER, subdomain);

    if (isCompressionEnabled()) {
      builder.addHeader(CONTENT_ENCODING_HEADER, GZIP_CONTENT_TYPE);
    }

    final Request request = builder.post(payload.toRequest()).build();
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
