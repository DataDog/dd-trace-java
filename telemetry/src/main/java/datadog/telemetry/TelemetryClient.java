package datadog.telemetry;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.HttpUtils;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpResponse;
import datadog.http.client.HttpUrl;
import datadog.trace.api.Config;
import datadog.trace.util.Strings;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryClient {

  public enum Result {
    SUCCESS,
    FAILURE,
    NOT_FOUND,
    INTERRUPTED
  }

  public static TelemetryClient buildAgentClient(
      HttpClient httpClient, HttpUrl agentUrl, HttpRetryPolicy.Factory httpRetryPolicy) {
    HttpUrl agentTelemetryUrl =
        agentUrl.newBuilder().addPathSegment(AGENT_TELEMETRY_API_ENDPOINT).build();
    return new TelemetryClient(httpClient, httpRetryPolicy, agentTelemetryUrl, null);
  }

  public static TelemetryClient buildIntakeClient(
      Config config, HttpRetryPolicy.Factory httpRetryPolicy) {
    String apiKey = config.getApiKey();
    if (apiKey == null) {
      log.debug("Cannot create Telemetry Intake because DD_API_KEY unspecified.");
      return null;
    }

    String telemetryUrl = buildIntakeTelemetryUrl(config);
    HttpUrl url;
    try {
      url = HttpUrl.parse(telemetryUrl);
    } catch (IllegalArgumentException e) {
      log.error("Can't create Telemetry URL for {}", telemetryUrl);
      return null;
    }

    long timeoutMillis = TimeUnit.SECONDS.toMillis(config.getAgentTimeout());
    HttpClient httpClient = HttpUtils.buildHttpClient(url, timeoutMillis);
    return new TelemetryClient(httpClient, httpRetryPolicy, url, apiKey);
  }

  private static String buildIntakeTelemetryUrl(Config config) {
    if (config.isCiVisibilityEnabled() && config.isCiVisibilityAgentlessEnabled()) {
      String agentlessUrl = config.getCiVisibilityAgentlessUrl();
      if (Strings.isNotBlank(agentlessUrl)) {
        return agentlessUrl + "/api/v2/apmtelemetry";
      }
    }
    return config.getDefaultTelemetryUrl();
  }

  private static final Logger log = LoggerFactory.getLogger(TelemetryClient.class);

  private static final String AGENT_TELEMETRY_API_ENDPOINT = "telemetry/proxy/api/v2/apmtelemetry";
  private static final String DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";

  private final HttpClient httpClient;
  private final HttpRetryPolicy.Factory httpRetryPolicy;
  private final HttpUrl url;
  private final String apiKey;

  public TelemetryClient(
      HttpClient httpClient, HttpRetryPolicy.Factory httpRetryPolicy, HttpUrl url, String apiKey) {
    this.httpClient = httpClient;
    this.httpRetryPolicy = httpRetryPolicy;
    this.url = url;
    this.apiKey = apiKey;
  }

  public HttpUrl getUrl() {
    return url;
  }

  public Result sendHttpRequest(HttpRequest.Builder httpRequestBuilder) {
    httpRequestBuilder.url(url);
    if (apiKey != null) {
      httpRequestBuilder.addHeader("DD-API-KEY", apiKey);
    }

    HttpRequest httpRequest = httpRequestBuilder.build();
    String requestType = httpRequest.header(DD_TELEMETRY_REQUEST_TYPE);

    try (HttpResponse response =
        HttpUtils.sendWithRetries(httpClient, httpRetryPolicy, httpRequest)) {
      if (response.code() == 404) {
        log.debug("Telemetry endpoint is disabled, dropping {} message.", requestType);
        return Result.NOT_FOUND;
      }
      if (!response.isSuccessful()) {
        log.debug("Telemetry message {} failed with: {}.", requestType, response.code());
        return Result.FAILURE;
      }
    } catch (InterruptedIOException e) {
      log.debug("Telemetry message {} sending interrupted: {}.", requestType, e.toString());
      return Result.INTERRUPTED;

    } catch (IOException e) {
      log.debug("Telemetry message {} failed with exception: {}.", requestType, e.toString());
      return Result.FAILURE;
    }

    log.debug("Telemetry message {} sent successfully to {}.", requestType, url);
    return Result.SUCCESS;
  }
}
