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

  public DDAgentMapperDiscovery(final DDAgentFeaturesDiscovery featuresDiscovery) {
    this.featuresDiscovery = featuresDiscovery;
  }

  private void reset() {
    this.traceMapper = null;
  }

  @Override
  public void discover() {
    reset();

    if (featuresDiscovery.getTraceEndpoint() == null) {
      featuresDiscovery.discover();
    }

    String tracesEndpoint = featuresDiscovery.getTraceEndpoint();

    switch (ProtocolVersion.fromTraceEndpoint(tracesEndpoint)) {
      case V1_0:
        traceMapper = new TraceMapperV1();
        break;

      case V0_5:
        traceMapper = new TraceMapperV0_5();
        break;

      default:
        traceMapper = new TraceMapperV0_4();
    }
  }

  @Override
  public RemoteMapper getMapper() {
    return traceMapper;
  }
}
