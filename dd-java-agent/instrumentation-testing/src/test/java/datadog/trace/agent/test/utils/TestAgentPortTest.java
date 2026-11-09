package datadog.trace.agent.test.utils;

import static datadog.trace.agent.test.utils.TestAgentPort.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestAgentPortTest {
  @Test
  void defaultsToTheSidecarPort() {
    assertEquals(8126, parse(null));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 8126, 65535})
  void acceptsValidPorts(int port) {
    assertEquals(port, parse(Integer.toString(port)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "0", "-1", "65536", "not-a-port", "2147483648"})
  void rejectsInvalidPorts(String value) {
    assertEquals(
        "CI_TEST_AGENT_PORT must be an integer between 1 and 65535",
        assertThrows(IllegalArgumentException.class, () -> parse(value)).getMessage());
  }
}
