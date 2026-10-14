package datadog.trace.instrumentation.openfeature;

import com.datadog.openfeature.internal.connector.EventProxyUnavailableException;
import com.datadog.openfeature.internal.connector.EventTransport;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import java.io.IOException;

/** Posts events through the Datadog Agent event platform proxy. */
public final class AgentEventProxy implements EventTransport {
  private final FeatureFlaggingGateway.Backend backend;

  AgentEventProxy(final FeatureFlaggingGateway.Backend backend) {
    this.backend = backend;
  }

  @Override
  public void post(final String route, final byte[] json) throws IOException {
    if (!this.backend.postEvent(route, json)) {
      throw new EventProxyUnavailableException("Datadog Agent event platform proxy unavailable");
    }
  }
}
