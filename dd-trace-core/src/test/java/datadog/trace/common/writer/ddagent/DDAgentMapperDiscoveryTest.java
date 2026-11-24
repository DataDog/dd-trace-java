package datadog.trace.common.writer.ddagent;

import static datadog.trace.api.ProtocolVersion.V0_4;
import static datadog.trace.api.ProtocolVersion.V0_5;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.common.writer.RemoteMapper;
import org.junit.jupiter.api.Test;

class DDAgentMapperDiscoveryTest {

  @Test
  void isNotOutdatedBeforeAMapperIsDiscovered() {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.getTraceEndpoint()).thenReturn("v0.5/traces");
    assertFalse(new DDAgentMapperDiscovery(features).isOutdated());
  }

  @Test
  void anotherEndpointForTheSameProtocolKeepsTheMapper() {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.getTraceEndpoint()).thenReturn("v0.4/traces");
    DDAgentMapperDiscovery discovery = new DDAgentMapperDiscovery(features);
    discovery.discover();
    RemoteMapper mapper = discovery.getMapper();

    // the v0.3 fallback takes v0.4 bytes
    when(features.getTraceEndpoint()).thenReturn("v0.3/traces");
    assertFalse(discovery.isOutdated());
    assertFalse(discovery.isOutdated());
    assertSame(mapper, discovery.getMapper());
  }

  @Test
  void isOutdatedOnceDiscoveryMovesToAnotherProtocol() {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.getTraceEndpoint()).thenReturn("v0.5/traces");
    DDAgentMapperDiscovery discovery = new DDAgentMapperDiscovery(features);
    discovery.discover();
    assertEquals(V0_5, ((TraceMapper) discovery.getMapper()).protocolVersion());

    when(features.getTraceEndpoint()).thenReturn("v0.4/traces");
    assertTrue(discovery.isOutdated());

    discovery.discover();
    assertEquals(V0_4, ((TraceMapper) discovery.getMapper()).protocolVersion());
    assertFalse(discovery.isOutdated());
  }
}
