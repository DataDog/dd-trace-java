package datadog.trace.api;

import static datadog.trace.api.ProtocolVersion.V0_4;
import static datadog.trace.api.ProtocolVersion.V0_5;
import static datadog.trace.api.ProtocolVersion.V1_0;
import static datadog.trace.api.ProtocolVersion.fromConfigValue;
import static datadog.trace.api.ProtocolVersion.fromTraceEndpoint;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("trace agent protocol versions")
class ProtocolVersionTest {

  @Test
  @DisplayName("expose configuration values")
  void exposesConfigValues() {
    assertEquals("0.4", V0_4.asConfigValue());
    assertEquals("0.5", V0_5.asConfigValue());
    assertEquals("1.0", V1_0.asConfigValue());
  }

  @Test
  @DisplayName("expose primary endpoints and fallbacks")
  void exposesPrimaryEndpointAndFallbacks() {
    assertEquals("v0.4/traces", V0_4.endpoint());
    assertEquals(singletonList("v0.3/traces"), V0_4.fallback());

    assertEquals("v0.5/traces", V0_5.endpoint());
    assertEquals(asList("v0.4/traces", "v0.3/traces"), V0_5.fallback());

    assertEquals("v1.0/traces", V1_0.endpoint());
    assertEquals(asList("v0.4/traces", "v0.3/traces"), V1_0.fallback());
  }

  @Test
  @DisplayName("preserve endpoint probe ordering")
  void preservesProbeOrdering() {
    assertEquals(asList("v0.4/traces", "v0.3/traces"), V0_4.endpointsToProbe());
    assertEquals(asList("v0.5/traces", "v0.4/traces", "v0.3/traces"), V0_5.endpointsToProbe());
    assertEquals(asList("v1.0/traces", "v0.4/traces", "v0.3/traces"), V1_0.endpointsToProbe());
  }

  @Test
  @DisplayName("map configuration values to protocol versions")
  void mapsConfigValueToVersion() {
    assertEquals(V0_4, fromConfigValue(null));
    assertEquals(V0_4, fromConfigValue("0.4"));
    assertEquals(V0_5, fromConfigValue("0.5"));
    assertEquals(V1_0, fromConfigValue("1.0"));
    assertEquals(V0_4, fromConfigValue("unsupported"));
  }

  @Test
  @DisplayName("map trace endpoints to protocol versions")
  void mapsTraceEndpointToVersion() {
    assertEquals(V0_4, fromTraceEndpoint(null));
    assertEquals(V1_0, fromTraceEndpoint("http://localhost:8126/v1.0/traces"));
    assertEquals(V0_5, fromTraceEndpoint("HTTP://LOCALHOST:8126/V0.5/TRACES"));
    assertEquals(V0_4, fromTraceEndpoint("v0.4/traces"));
    assertEquals(V0_4, fromTraceEndpoint("http://localhost:8126/unsupported"));
  }
}
