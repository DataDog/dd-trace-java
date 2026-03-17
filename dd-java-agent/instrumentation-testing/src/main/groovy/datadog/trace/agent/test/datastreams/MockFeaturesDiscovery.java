package datadog.trace.agent.test.datastreams;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.Monitoring;
import datadog.trace.api.ProtocolVersion;

// TODO Ideally, DDAgentFeaturesDiscovery would be an interface to create a proper testable stubs
public class MockFeaturesDiscovery extends DDAgentFeaturesDiscovery {
  private final boolean supportsDataStreams;

  public MockFeaturesDiscovery(boolean supportsDataStreams) {
    super(null, Monitoring.DISABLED, null, ProtocolVersion.V0_5, true);
    this.supportsDataStreams = supportsDataStreams;
  }

  @Override
  public void discover() {}

  @Override
  public void discoverIfOutdated() {}

  @Override
  public boolean supportsDataStreams() {
    return supportsDataStreams;
  }
}
