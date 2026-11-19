package datadog.trace.common.writer.ddagent;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.ProtocolVersion;
import datadog.trace.common.writer.RemoteMapper;
import datadog.trace.common.writer.RemoteMapperDiscovery;

/**
 * Mapper discovery logic when the DDAgent is used. It leverages the {@code
 * DDAgentFeaturesDiscovery} to select the correct {@code TraceMapper}. Typically, an instance of
 * this class is used during the mapper lazy loading in the {@code PayloadDispatcher} class.
 */
public class DDAgentMapperDiscovery implements RemoteMapperDiscovery {

  private final DDAgentFeaturesDiscovery featuresDiscovery;
  private TraceMapper traceMapper;

  /** The trace endpoint the mapper was chosen for, updated while later ones keep its protocol. */
  private String selectedEndpoint;

  public DDAgentMapperDiscovery(final DDAgentFeaturesDiscovery featuresDiscovery) {
    this.featuresDiscovery = featuresDiscovery;
  }

  private void reset() {
    this.traceMapper = null;
    this.selectedEndpoint = null;
  }

  @Override
  public void discover() {
    reset();

    if (featuresDiscovery.getTraceEndpoint() == null) {
      featuresDiscovery.discover();
    }

    String tracesEndpoint = featuresDiscovery.getTraceEndpoint();
    if (tracesEndpoint == null) {
      return;
    }
    selectedEndpoint = tracesEndpoint;

    switch (ProtocolVersion.fromTraceEndpoint(tracesEndpoint)) {
      case V1_0:
        traceMapper = new TraceMapperV1();
        break;

      case V0_5:
        traceMapper = new TraceMapperV0_5();
        break;

      default:
        traceMapper = new TraceMapperV0_4();
        break;
    }
  }

  @Override
  public RemoteMapper getMapper() {
    return traceMapper;
  }

  /**
   * True once discovery has moved to an endpoint of another protocol than the mapper encodes, for
   * instance after the agent restarted as another version. Not thread-safe: called by the one
   * thread that encodes traces.
   */
  @Override
  public boolean isOutdated() {
    String endpoint = featuresDiscovery.getTraceEndpoint();
    if (traceMapper == null || endpoint == null || endpoint.equals(selectedEndpoint)) {
      return false;
    }

    if (ProtocolVersion.fromTraceEndpoint(endpoint) == traceMapper.protocolVersion()) {
      // another endpoint for the same protocol, such as the v0.3 fallback: keep the mapper
      selectedEndpoint = endpoint;
      return false;
    }

    return true;
  }
}
