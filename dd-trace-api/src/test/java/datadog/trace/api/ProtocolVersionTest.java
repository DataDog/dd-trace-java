package datadog.trace.api;

import static datadog.trace.api.ProtocolVersion.V0_4;
import static datadog.trace.api.ProtocolVersion.V0_5;
import static datadog.trace.api.ProtocolVersion.V1_0;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProtocolVersionTest {

  @Test
  void exposesPrimaryEndpointAndFallbacks() {
    assertEquals("v0.4/traces", V0_4.endpoint());
    assertEquals(singletonList("v0.3/traces"), V0_4.fallback());

    assertEquals("v0.5/traces", V0_5.endpoint());
    assertEquals(asList("v0.4/traces", "v0.3/traces"), V0_5.fallback());

    assertEquals("v1.0/traces", V1_0.endpoint());
    assertEquals(asList("v0.4/traces", "v0.3/traces"), V1_0.fallback());
  }

  @Test
  void preservesProbeOrdering() {
    assertEquals(asList("v0.4/traces", "v0.3/traces"), V0_4.endpointsToProbe());
    assertEquals(asList("v0.5/traces", "v0.4/traces", "v0.3/traces"), V0_5.endpointsToProbe());
    assertEquals(asList("v1.0/traces", "v0.4/traces", "v0.3/traces"), V1_0.endpointsToProbe());
  }
}
