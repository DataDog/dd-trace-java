package datadog.telemetry;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.Config;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryHttpClient {
  private static final Logger log = LoggerFactory.getLogger(TelemetryHttpClient.class);

  private static final String DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";
  private static final String AGENT_TELEMETRY_API_ENDPOINT = "telemetry/proxy/api/v2/apmtelemetry";

  public enum Result {
    SUCCESS,
    FAILURE,
    NOT_FOUND;
  }

  private enum TelemetryReceiver {
    AGENT,
    INTAKE;
  }

  private final DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery;
  private final OkHttpClient httpClient;

  private final HttpUrl agentTelemetryUrl;

  private TelemetryReceiver telemetryReceiver = null;
  private final String apiKey;
  private boolean errorReported;
  private boolean missingApiKeyReported;

  public TelemetryHttpClient(
      DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery,
      OkHttpClient httpClient,
      HttpUrl agentUrl) {
    this(ddAgentFeaturesDiscovery, httpClient, agentUrl, Config.get().getApiKey());
  }

  TelemetryHttpClient(
      DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery,
      OkHttpClient httpClient,
      HttpUrl agentUrl,
      String apiKey) {
    this.ddAgentFeaturesDiscovery = ddAgentFeaturesDiscovery;
    this.httpClient = httpClient;
    this.agentTelemetryUrl =
        agentUrl.newBuilder().addPathSegments(AGENT_TELEMETRY_API_ENDPOINT).build();
    this.apiKey = apiKey;
  }

  public Result sendRequest(TelemetryRequest request) {
    ddAgentFeaturesDiscovery.discoverIfOutdated();
    boolean agentSupportsTelemetryProxy = ddAgentFeaturesDiscovery.supportsTelemetryProxy();

    if (telemetryReceiver == null) {
      if (!agentSupportsTelemetryProxy && apiKey != null) {
        telemetryReceiver = TelemetryReceiver.INTAKE;
      } else {
        telemetryReceiver = TelemetryReceiver.AGENT;
      }
      log.info("Telemetry will be sent to {} as of now", telemetryReceiver);
    }

    String requestApiKey = telemetryReceiver == TelemetryReceiver.INTAKE ? apiKey : null;
    Request httpRequest = request.httpRequest(currentTelemetryUrl(), requestApiKey);
    Result result = sendHttpRequest(httpRequest);

    switch (telemetryReceiver) {
      case AGENT:
        if (result != Result.SUCCESS) {
          reportErrorOnce(result);
          if (apiKey != null) {
            log.info(
                "Agent Telemetry endpoint failed. Telemetry will be sent to Intake as of now.");
            telemetryReceiver = TelemetryReceiver.INTAKE;
            errorReported = false;
          } else if (!missingApiKeyReported) {
            log.error("Cannot use Intake to send telemetry because unset API_KEY.");
            missingApiKeyReported = true;
          }
        }
        break;
      case INTAKE:
        if (result != Result.SUCCESS) {
          reportErrorOnce(result);
        }
        if (agentSupportsTelemetryProxy) {
          log.info(
              "Agent Telemetry endpoint is now available. Telemetry will be sent to Agent as of now.");
          telemetryReceiver = TelemetryReceiver.AGENT;
          errorReported = false;
        } else if (result != Result.SUCCESS) {
          log.info("Intake Telemetry endpoint failed. Telemetry will be sent to Agent as of now.");
          telemetryReceiver = TelemetryReceiver.AGENT;
          errorReported = false;
        }
        break;
    }

    return result;
  }

  private HttpUrl currentTelemetryUrl() {
    if (telemetryReceiver == TelemetryReceiver.AGENT) {
      return agentTelemetryUrl;
    }
    // TODO pass as a param or get from config
    return HttpUrl.parse("https://instrumentation-telemetry-intake.datadoghq.com");
  }

  protected Result sendHttpRequest(Request httpRequest) {
    String requestType = httpRequest.header(DD_TELEMETRY_REQUEST_TYPE);
    try (Response response = httpClient.newCall(httpRequest).execute()) {
      if (response.code() == 404) {
        log.debug("Telemetry endpoint is disabled, dropping {} message", requestType);
        return Result.NOT_FOUND;
      }
      if (!response.isSuccessful()) {
        log.debug(
            "Telemetry message {} failed with: {} {} ",
            requestType,
            response.code(),
            response.message());
        return Result.FAILURE;
      }
    } catch (IOException e) {
      log.debug("Telemetry message {} failed with exception: {}", requestType, e.toString());
      return Result.FAILURE;
    }

    log.debug("Telemetry message {} sent successfully", requestType);
    return Result.SUCCESS;
  }

  private void reportErrorOnce(Result result) {
    if (!errorReported) {
      log.warn("Got {} sending telemetry request to {}", result, currentTelemetryUrl());
      errorReported = true;
    }
  }
}
