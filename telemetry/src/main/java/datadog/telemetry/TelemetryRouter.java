package datadog.telemetry;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryRouter {
  private static final Logger log = LoggerFactory.getLogger(TelemetryRouter.class);

  private final DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery;
  private final TelemetryClient agentClient;
  private final TelemetryClient intakeClient;
  private final boolean useIntakeClientByDefault;
  private TelemetryClient currentClient;
  private boolean errorReported;

  public TelemetryRouter(
      DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery,
      TelemetryClient agentClient,
      @Nullable TelemetryClient intakeClient,
      boolean useIntakeClientByDefault) {
    this.ddAgentFeaturesDiscovery = ddAgentFeaturesDiscovery;
    this.agentClient = agentClient;
    this.intakeClient = intakeClient;
    this.useIntakeClientByDefault = useIntakeClientByDefault;
    this.currentClient = useIntakeClientByDefault ? intakeClient : agentClient;
  }

  public TelemetryClient.Result sendRequest(TelemetryRequest request) {
    ddAgentFeaturesDiscovery.discoverIfOutdated();
    boolean agentSupportsTelemetryProxy = ddAgentFeaturesDiscovery.supportsTelemetryProxy();

    Request.Builder httpRequestBuilder = request.httpRequest();
    TelemetryClient.Result result = currentClient.sendHttpRequest(httpRequestBuilder);

    boolean requestFailed = result != TelemetryClient.Result.SUCCESS;
    if (currentClient == agentClient) {
      if (requestFailed) {
        reportErrorOnce(currentClient.getUrl(), result);
        if (intakeClient != null) {
          log.info("Agent Telemetry endpoint failed. Telemetry will be sent to Intake.");
          errorReported = false;
          currentClient = intakeClient;
        }
      }
    } else {
      if (requestFailed) {
        reportErrorOnce(currentClient.getUrl(), result);
      }
      if ((agentSupportsTelemetryProxy && !useIntakeClientByDefault) || requestFailed) {
        errorReported = false;
        if (requestFailed) {
          log.info("Intake Telemetry endpoint failed. Telemetry will be sent to Agent.");
        } else {
          log.info("Agent Telemetry endpoint is now available. Telemetry will be sent to Agent.");
        }
        currentClient = agentClient;
      }
    }

    return result;
  }

  private void reportErrorOnce(HttpUrl requestUrl, TelemetryClient.Result result) {
    if (!errorReported) {
      log.warn("Got {} sending telemetry request to {}.", result, requestUrl);
      errorReported = true;
    }
  }
}
