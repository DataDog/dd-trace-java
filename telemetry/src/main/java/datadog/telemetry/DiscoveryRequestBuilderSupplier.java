package datadog.telemetry;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.function.Supplier;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscoveryRequestBuilderSupplier implements Supplier<RequestBuilder> {

  private static final Logger log = LoggerFactory.getLogger(DiscoveryRequestBuilderSupplier.class);
  private RequestBuilder requestBuilder;
  private final SharedCommunicationObjects sco;
  private DDAgentFeaturesDiscovery featuresDiscovery;

  DiscoveryRequestBuilderSupplier(final SharedCommunicationObjects sco) {
    this.sco = sco;
  }

  @Override
  public synchronized RequestBuilder get() {
    if (requestBuilder != null) {
      return requestBuilder;
    }

    if (featuresDiscovery == null) {
      // Note that feature discovery initialization also runs discovery on its first call.
      featuresDiscovery = sco.featuresDiscovery(Config.get());
    } else {
      // Feature discovery might have run elsewhere. In that case, we don't need another call.
      if (featuresDiscovery.getTelemetryEndpoint() == null) {
        featuresDiscovery.discover();
      }
    }

    String telemetryEndpoint = featuresDiscovery.getTelemetryEndpoint();
    if (telemetryEndpoint == null) {
      log.debug("Telemetry endpoint not found");
      return null;
    }

    HttpUrl httpUrl = featuresDiscovery.buildUrl(telemetryEndpoint + RequestBuilder.API_ENDPOINT);
    if (httpUrl == null) {
      log.debug("Unable to build Telemetry httpUrl for '{}{}'", telemetryEndpoint, RequestBuilder.API_ENDPOINT);
      return null;
    }

    requestBuilder = new RequestBuilder(httpUrl);
    return requestBuilder;
  }

  public synchronized void needRediscover() {
    sco.setFeaturesDiscovery(null);
    featuresDiscovery = null;
    requestBuilder = null;
  }
}
