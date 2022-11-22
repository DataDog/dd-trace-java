package datadog.telemetry;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import okhttp3.HttpUrl;

public class AgentDiscoverer {
  private final SharedCommunicationObjects sco;

  public AgentDiscoverer(SharedCommunicationObjects sco) {
    this.sco = sco;
  }

  public HttpUrl discoverTelemetryEndpoint() {
    DDAgentFeaturesDiscovery fd = sco.featuresDiscovery(Config.get());
    if (fd == null) {
      return null;
    }

    fd.discoverIfOutdated();

    String telemetryEndpoint = fd.getTelemetryEndpoint();
    if (telemetryEndpoint == null) {
      return null;
    }

    return fd.buildUrl(telemetryEndpoint);
  }

  public RequestBuilder telemetryRequestBuilder() {
    RequestBuilder requestBuilder = null;
    HttpUrl httpUrl = discoverTelemetryEndpoint();
    if (httpUrl != null) {
      requestBuilder = new RequestBuilder(httpUrl);
    }
    return requestBuilder;
  }
}
