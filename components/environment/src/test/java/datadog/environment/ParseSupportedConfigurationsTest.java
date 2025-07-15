package datadog.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParseSupportedConfigurationsTest {
  @Test
  void testParsingRealJsonFile() {
    ParseSupportedConfigurations.loadSupportedConfigurations("test-supported-configurations.json");

    assertTrue(ParseSupportedConfigurations.supportedConfigurations.contains("DD_AGENT_HOST"));
    assertEquals(
        "DD_AGENT_HOST", ParseSupportedConfigurations.aliasMapping.get("DD_TRACE_AGENT_HOSTNAME"));
    assertEquals("NEW_KEY", ParseSupportedConfigurations.deprecatedConfigurations.get("OLD_KEY"));
  }
}
