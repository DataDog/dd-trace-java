package datadog.communication.ddagent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AgentVersionTest {

  @Test
  void testIsVersionBelow_VersionBelowThreshold() {
    assertTrue(AgentVersion.isVersionBelow("7.64.0", 7, 65, 0));
    assertTrue(AgentVersion.isVersionBelow("7.64.9", 7, 65, 0));
    assertTrue(AgentVersion.isVersionBelow("6.99.99", 7, 65, 0));
    assertTrue(AgentVersion.isVersionBelow("7.0.0", 7, 65, 0));
  }

  @Test
  void testIsVersionBelow_VersionEqualToThreshold() {
    assertFalse(AgentVersion.isVersionBelow("7.65.0", 7, 65, 0));
  }

  @Test
  void testIsVersionBelow_VersionAboveThreshold() {
    assertFalse(AgentVersion.isVersionBelow("7.65.1", 7, 65, 0));
    assertFalse(AgentVersion.isVersionBelow("7.66.0", 7, 65, 0));
    assertFalse(AgentVersion.isVersionBelow("8.0.0", 7, 65, 0));
    assertFalse(AgentVersion.isVersionBelow("7.65.10", 7, 65, 0));
  }

  @Test
  void testIsVersionBelow_MajorVersionComparison() {
    assertTrue(AgentVersion.isVersionBelow("6.100.100", 7, 0, 0));
    assertFalse(AgentVersion.isVersionBelow("8.0.0", 7, 100, 100));
  }

  @Test
  void testIsVersionBelow_MinorVersionComparison() {
    assertTrue(AgentVersion.isVersionBelow("7.64.100", 7, 65, 0));
    assertFalse(AgentVersion.isVersionBelow("7.66.0", 7, 65, 100));
  }

  @Test
  void testIsVersionBelow_WithSuffix() {
    assertTrue(AgentVersion.isVersionBelow("7.64.0-rc.1", 7, 65, 0));
    assertTrue(AgentVersion.isVersionBelow("7.65.0-rc.1", 7, 65, 0));
    assertFalse(AgentVersion.isVersionBelow("7.65.1-snapshot", 7, 65, 0));
  }

  @Test
  void testIsVersionBelow_NullOrEmpty() {
    assertTrue(AgentVersion.isVersionBelow(null, 7, 65, 0));
    assertTrue(AgentVersion.isVersionBelow("", 7, 65, 0));
  }

  @Test
  void testIsVersionBelow_InvalidFormat() {
    assertTrue(AgentVersion.isVersionBelow("invalid", 7, 65, 0));
    assertTrue(AgentVersion.isVersionBelow("7", 7, 65, 0));
    assertTrue(AgentVersion.isVersionBelow("7.", 7, 65, 0));
    assertTrue(AgentVersion.isVersionBelow("7.65", 7, 65, 0));
    assertTrue(AgentVersion.isVersionBelow("7.65.", 7, 65, 0));
    assertTrue(AgentVersion.isVersionBelow("a.b.c", 7, 65, 0));
  }
}
