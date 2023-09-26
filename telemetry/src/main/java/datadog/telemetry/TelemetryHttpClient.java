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
  private static final String DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";
  private static final String AGENT_TELEMETRY_API_ENDPOINT = "telemetry/proxy/api/v2/apmtelemetry";

  public enum Result {
    SUCCESS,
    FAILURE,
    NOT_FOUND;
  }

  private static final Logger log = LoggerFactory.getLogger(TelemetryHttpClient.class);

  private final DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery;
  private final OkHttpClient httpClient;
  private final HttpUrl agentTelemetryUrl;

  enum TelemetryReceiver {
    AGENT,
    INTAKE;
  }

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

    if (telemetryReceiver == null) {
      chooseTelemetryReceiver();
    }

    Request httpRequest = request.httpRequest(currentTelemetryUrl(), currentApiKey());
    Result result = sendRequest(httpRequest);

    switchTelemetryReceiverIfNeeded(result);

    return result;
  }

  private void chooseTelemetryReceiver() {
    if (apiKey != null && ddAgentFeaturesDiscovery.supportsTelemetryProxy()) {
      log.info("Will send telemetry to Intake");
      telemetryReceiver = TelemetryReceiver.INTAKE;
    } else {
      telemetryReceiver = TelemetryReceiver.AGENT;
      log.info("Will send telemetry to Agent");
    }
  }

  private void switchTelemetryReceiverIfNeeded(Result result) {
    switch (telemetryReceiver) {
      case AGENT:
        if (result != Result.SUCCESS) {
          reportErrorOnce(result);
          if (apiKey != null) {
            telemetryReceiver = TelemetryReceiver.INTAKE;
            errorReported = false;
          } else if (!missingApiKeyReported) {
            log.error("Cannot use Intake to send telemetry because unset API_KEY");
            missingApiKeyReported = true;
          }
        }
        break;
      case INTAKE:
        if (result != Result.SUCCESS) {
          reportErrorOnce(result);
        } else if (ddAgentFeaturesDiscovery.supportsTelemetryProxy()) {
          log.info("Agent Telemetry endpoint is now available. Will send telemetry to Agent now");
          telemetryReceiver = TelemetryReceiver.AGENT;
          errorReported = false;
        }
        break;
    }
  }

  private HttpUrl currentTelemetryUrl() {
    if (telemetryReceiver == TelemetryReceiver.AGENT) {
      return agentTelemetryUrl;
    }
    return HttpUrl.parse(
        "https://instrumentation-telemetry-intake.datadoghq.com"); // TODO pass as a param or get
    // from config
  }

  private String currentApiKey() {
    if (telemetryReceiver == TelemetryReceiver.INTAKE) {
      return apiKey;
    }
    return null;
  }

  private void reportErrorOnce(Result result) {
    if (!errorReported) {
      log.error("Failed with {} sending telemetry request to {}", result, currentTelemetryUrl());
      errorReported = true;
    }
  }

  protected Result sendRequest(Request httpRequest) {
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
}
