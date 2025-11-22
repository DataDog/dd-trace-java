package datadog.telemetry;

import com.antithesis.sdk.Assert;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.util.Strings;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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
      OkHttpClient okHttpClient, HttpUrl agentUrl, HttpRetryPolicy.Factory httpRetryPolicy) {
    HttpUrl agentTelemetryUrl =
        agentUrl.newBuilder().addPathSegments(AGENT_TELEMETRY_API_ENDPOINT).build();
    return new TelemetryClient(okHttpClient, httpRetryPolicy, agentTelemetryUrl, null);
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
      url = HttpUrl.get(telemetryUrl);
    } catch (IllegalArgumentException e) {
      log.error("Can't create Telemetry URL for {}", telemetryUrl);
      return null;
    }

    long timeoutMillis = TimeUnit.SECONDS.toMillis(config.getAgentTimeout());
    OkHttpClient httpClient = OkHttpUtils.buildHttpClient(url, timeoutMillis);
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

  private final OkHttpClient okHttpClient;
  private final HttpRetryPolicy.Factory httpRetryPolicy;
  private final HttpUrl url;
  private final String apiKey;

  public TelemetryClient(
      OkHttpClient okHttpClient,
      HttpRetryPolicy.Factory httpRetryPolicy,
      HttpUrl url,
      String apiKey) {
    this.okHttpClient = okHttpClient;
    this.httpRetryPolicy = httpRetryPolicy;
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
    
    // Antithesis: Track telemetry sending attempts
    log.debug("ANTITHESIS_ASSERT: Telemetry sending exercised (reachable) - request_type: {}", requestType);
    Assert.reachable("Telemetry sending is exercised", null);

    try (okhttp3.Response response =
        OkHttpUtils.sendWithRetries(okHttpClient, httpRetryPolicy, httpRequest)) {
      
      // Antithesis: Assert that all telemetry requests should succeed
      ObjectNode telemetryResponseDetails = JsonNodeFactory.instance.objectNode();
      telemetryResponseDetails.put("request_type", requestType != null ? requestType : "unknown");
      telemetryResponseDetails.put("http_status", response.code());
      telemetryResponseDetails.put("http_message", response.message());
      telemetryResponseDetails.put("url", url.toString());
      telemetryResponseDetails.put("success", response.isSuccessful());
      
      if (response.code() == 404) {
        // Antithesis: Track 404 - endpoint disabled scenario
        ObjectNode notFoundDetails = JsonNodeFactory.instance.objectNode();
        notFoundDetails.put("request_type", requestType != null ? requestType : "unknown");
        notFoundDetails.put("url", url.toString());
        notFoundDetails.put("reason", "endpoint_disabled_404");
        
        log.debug("ANTITHESIS_ASSERT: Telemetry endpoint 404 (sometimes) - request_type: {}, url: {}", requestType, url);
        Assert.sometimes(
            response.code() == 404,
            "Telemetry endpoint returns 404 - endpoint may be disabled",
            notFoundDetails);
        
        log.debug("Telemetry endpoint is disabled, dropping {} message.", requestType);
        return Result.NOT_FOUND;
      }
      
      if (!response.isSuccessful()) {
        // Antithesis: Telemetry should not fail - data should be retried/buffered
        ObjectNode failureDetails = JsonNodeFactory.instance.objectNode();
        failureDetails.put("request_type", requestType != null ? requestType : "unknown");
        failureDetails.put("http_status", response.code());
        failureDetails.put("http_message", response.message());
        failureDetails.put("url", url.toString());
        failureDetails.put("reason", "http_error_response");
        
        log.debug("ANTITHESIS_ASSERT: Telemetry HTTP request failed (unreachable) - request_type: {}, status: {}", requestType, response.code());
        Assert.unreachable(
            "Telemetry HTTP request failed - telemetry data should not be dropped, should retry",
            failureDetails);
        
        log.debug(
            "Telemetry message {} failed with: {} {}.",
            requestType,
            response.code(),
            response.message());
        return Result.FAILURE;
      }
      
      // Antithesis: Assert success
      log.debug("ANTITHESIS_ASSERT: Checking telemetry request success (always) - successful: {}, request_type: {}", response.isSuccessful(), requestType);
      Assert.always(
          response.isSuccessful(),
          "Telemetry requests should always succeed - no telemetry data should be lost",
          telemetryResponseDetails);
          
    } catch (InterruptedIOException e) {
      log.debug("Telemetry message {} sending interrupted: {}.", requestType, e.toString());
      return Result.INTERRUPTED;

    } catch (IOException e) {
      // Antithesis: Network failures should not cause telemetry loss
      ObjectNode ioErrorDetails = JsonNodeFactory.instance.objectNode();
      ioErrorDetails.put("request_type", requestType != null ? requestType : "unknown");
      ioErrorDetails.put("exception_type", e.getClass().getName());
      ioErrorDetails.put("exception_message", e.getMessage());
      ioErrorDetails.put("url", url.toString());
      ioErrorDetails.put("reason", "network_io_exception");
      
      log.debug("ANTITHESIS_ASSERT: Telemetry network/IO exception (unreachable) - request_type: {}, exception: {}", requestType, e.getClass().getName());
      Assert.unreachable(
          "Telemetry network/IO failure - telemetry data should not be dropped, should retry",
          ioErrorDetails);
      
      log.debug("Telemetry message {} failed with exception: {}.", requestType, e.toString());
      return Result.FAILURE;
    }

    log.debug("Telemetry message {} sent successfully to {}.", requestType, url);
    return Result.SUCCESS;
  }
}
