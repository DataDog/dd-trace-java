package datadog.telemetry;

import com.antithesis.sdk.Assert;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    boolean requestFailed =
        result != TelemetryClient.Result.SUCCESS
            // interrupted request is most likely due to telemetry system shutdown,
            // we do not want to log errors and reattempt in this case
            && result != TelemetryClient.Result.INTERRUPTED;
    
    // Antithesis: Track telemetry routing and failover behavior
    ObjectNode routingDetails = JsonNodeFactory.instance.objectNode();
    routingDetails.put("result", result.toString());
    routingDetails.put("current_client", currentClient == agentClient ? "agent" : "intake");
    routingDetails.put("request_failed", requestFailed);
    routingDetails.put("has_fallback", intakeClient != null);
    routingDetails.put("url", currentClient.getUrl().toString());
    
    log.debug("ANTITHESIS_ASSERT: Checking telemetry routing success (always) - result: {}, client: {}", result, currentClient == agentClient ? "agent" : "intake");
    Assert.always(
        result == TelemetryClient.Result.SUCCESS || result == TelemetryClient.Result.INTERRUPTED,
        "Telemetry routing should always succeed - failures indicate data loss without retry mechanism",
        routingDetails);
    
    if (currentClient == agentClient) {
      if (requestFailed) {
        // Antithesis: Track agent telemetry failures
        ObjectNode agentFailureDetails = JsonNodeFactory.instance.objectNode();
        agentFailureDetails.put("result", result.toString());
        agentFailureDetails.put("url", currentClient.getUrl().toString());
        agentFailureDetails.put("has_intake_fallback", intakeClient != null);
        agentFailureDetails.put("reason", "agent_telemetry_failure");
        
        log.debug("ANTITHESIS_ASSERT: Agent telemetry endpoint failed (unreachable) - result: {}, has_fallback: {}", result, intakeClient != null);
        Assert.unreachable(
            "Agent telemetry endpoint failed - switching to intake but current request data is lost",
            agentFailureDetails);
        
        reportErrorOnce(currentClient.getUrl(), result);
        if (intakeClient != null) {
          log.info("Agent Telemetry endpoint failed. Telemetry will be sent to Intake.");
          errorReported = false;
          currentClient = intakeClient;
        }
      }
    } else {
      if (requestFailed) {
        // Antithesis: Track intake telemetry failures
        ObjectNode intakeFailureDetails = JsonNodeFactory.instance.objectNode();
        intakeFailureDetails.put("result", result.toString());
        intakeFailureDetails.put("url", currentClient.getUrl().toString());
        intakeFailureDetails.put("will_fallback_to_agent", true);
        intakeFailureDetails.put("reason", "intake_telemetry_failure");
        
        log.debug("ANTITHESIS_ASSERT: Intake telemetry endpoint failed (unreachable) - result: {}, will_fallback: true", result);
        Assert.unreachable(
            "Intake telemetry endpoint failed - switching to agent but current request data is lost",
            intakeFailureDetails);
        
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
