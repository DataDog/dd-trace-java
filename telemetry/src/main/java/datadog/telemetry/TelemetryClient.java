package datadog.telemetry;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.util.Strings;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryClient {

  public enum Result {
    SUCCESS,
    FAILURE,
    NOT_FOUND;
  }

  public static TelemetryClient buildAgentClient(OkHttpClient okHttpClient, HttpUrl agentUrl) {
    HttpUrl agentTelemetryUrl =
        agentUrl.newBuilder().addPathSegments(AGENT_TELEMETRY_API_ENDPOINT).build();
    return new TelemetryClient(okHttpClient, agentTelemetryUrl, null);
  }

  public static TelemetryClient buildIntakeClient(Config config) {
    String apiKey = config.getApiKey();
    if (apiKey == null) {
      log.debug("Cannot create Telemetry Intake because DD_API_KEY unspecified.");
      return null;
    }

    String telemetryUrl = buildIntakeTelemetryUrl(config);
    HttpUrl url;
    try {
      url = HttpUrl.get(telemetryUrl);
    } catch (IllegalArgumentException e) {
      log.error("Can't create Telemetry URL for {}", telemetryUrl);
      return null;
    }

    long timeoutMillis = TimeUnit.SECONDS.toMillis(config.getAgentTimeout());
    OkHttpClient httpClient = OkHttpUtils.buildHttpClient(url, timeoutMillis);
    return new TelemetryClient(httpClient, url, apiKey);
  }

  private static String buildIntakeTelemetryUrl(Config config) {
    if (config.isCiVisibilityEnabled() && config.isCiVisibilityAgentlessEnabled()) {
      String agentlessUrl = config.getCiVisibilityAgentlessUrl();
      if (Strings.isNotBlank(agentlessUrl)) {
        return agentlessUrl + "/api/v2/apmtelemetry";
      }
    }

    String site = config.getSite();
    String prefix = "";
    if (site.endsWith("datad0g.com")) {
      prefix = "all-http-intake.logs.";
    } else if (site.endsWith("datadoghq.com") || site.endsWith("datadoghq.eu")) {
      prefix = "instrumentation-telemetry-intake.";
    }
    return "https://" + prefix + site + "/api/v2/apmtelemetry";
  }

  private static final Logger log = LoggerFactory.getLogger(TelemetryClient.class);

  private static final String AGENT_TELEMETRY_API_ENDPOINT = "telemetry/proxy/api/v2/apmtelemetry";
  private static final String DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";

  private final OkHttpClient okHttpClient;
  private final HttpUrl url;
  private final String apiKey;

  public TelemetryClient(OkHttpClient okHttpClient, HttpUrl url, String apiKey) {
    this.okHttpClient = okHttpClient;
    this.url = url;
    this.apiKey = apiKey;
  }

  public HttpUrl getUrl() {
    return url;
  }

  public Result sendHttpRequest(Request.Builder httpRequestBuilder) {
    httpRequestBuilder.url(url);
    if (apiKey != null) {
      httpRequestBuilder.addHeader("DD-API-KEY", apiKey);
    }

    Request httpRequest = httpRequestBuilder.build();
    String requestType = httpRequest.header(DD_TELEMETRY_REQUEST_TYPE);
    try (Response response = okHttpClient.newCall(httpRequest).execute()) {
      if (response.code() == 404) {
        log.debug("Telemetry endpoint is disabled, dropping {} message.", requestType);
        return Result.NOT_FOUND;
      }
      if (!response.isSuccessful()) {
        log.debug(
            "Telemetry message {} failed with: {} {}.",
            requestType,
            response.code(),
            response.message());
        return Result.FAILURE;
      }
    } catch (IOException e) {
      log.debug("Telemetry message {} failed with exception: {}.", requestType, e.toString());
      return Result.FAILURE;
    }

    log.debug("Telemetry message {} sent successfully to {}.", requestType, url);
    return Result.SUCCESS;
  }
}
