package datadog.telemetry;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
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

  private TelemetryReceiver telemetryReceiver;
  private final HttpUrl intakeUrl;
  private final String apiKey;
  private boolean errorReported;
  private boolean missingApiKeyReported;

  public TelemetryHttpClient(
      DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery,
      OkHttpClient httpClient,
      HttpUrl agentUrl,
      HttpUrl intakeUrl,
      String apiKey) {
    this.ddAgentFeaturesDiscovery = ddAgentFeaturesDiscovery;
    this.httpClient = httpClient;
    this.agentTelemetryUrl =
        agentUrl.newBuilder().addPathSegments(AGENT_TELEMETRY_API_ENDPOINT).build();
    this.intakeUrl = intakeUrl;
    this.apiKey = apiKey;
  }

  public Result sendRequest(TelemetryRequest request) {
    ddAgentFeaturesDiscovery.discoverIfOutdated();
    boolean agentSupportsTelemetryProxy = ddAgentFeaturesDiscovery.supportsTelemetryProxy();

    if (telemetryReceiver == null) {
      if (!agentSupportsTelemetryProxy && apiKey != null && intakeUrl != null) {
        telemetryReceiver = TelemetryReceiver.INTAKE;
      } else {
        telemetryReceiver = TelemetryReceiver.AGENT;
      }
      log.info("Telemetry will be sent to {}.", telemetryReceiver);
    }

    HttpUrl requestUrl = agentTelemetryUrl;
    String requestApiKey = null;
    if (telemetryReceiver == TelemetryReceiver.INTAKE && apiKey != null && intakeUrl != null) {
      requestUrl = intakeUrl;
      requestApiKey = apiKey;
    }

    Request httpRequest = request.httpRequest(requestUrl, requestApiKey);
    Result result = sendHttpRequest(httpRequest);

    boolean requestFailed = result != Result.SUCCESS;
    switch (telemetryReceiver) {
      case AGENT:
        if (requestFailed) {
          reportErrorOnce(requestUrl, result);
          if (apiKey != null && intakeUrl != null) {
            log.info("Agent Telemetry endpoint failed. Telemetry will be sent to Intake.");
            telemetryReceiver = TelemetryReceiver.INTAKE;
            errorReported = false;
          } else if (!missingApiKeyReported) {
            log.error("Cannot use Intake to send telemetry because unset API_KEY.");
            missingApiKeyReported = true;
          }
        }
        break;
      case INTAKE:
        if (requestFailed) {
          reportErrorOnce(requestUrl, result);
        }
        if (agentSupportsTelemetryProxy || requestFailed) {
          telemetryReceiver = TelemetryReceiver.AGENT;
          errorReported = false;
          if (agentSupportsTelemetryProxy) {
            log.info("Agent Telemetry endpoint is now available. Telemetry will be sent to Agent.");
          } else {
            log.info("Intake Telemetry endpoint failed. Telemetry will be sent to Agent.");
          }
        }
        break;
    }

    return result;
  }

  private void reportErrorOnce(HttpUrl requestUrl, Result result) {
    if (!errorReported) {
      log.warn("Got {} sending telemetry request to {}.", result, requestUrl);
      errorReported = true;
    }
  }

  protected Result sendHttpRequest(Request httpRequest) {
    String requestType = httpRequest.header(DD_TELEMETRY_REQUEST_TYPE);
    try (Response response = httpClient.newCall(httpRequest).execute()) {
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

    log.debug("Telemetry message {} sent successfully.", requestType);
    return Result.SUCCESS;
  }
}
