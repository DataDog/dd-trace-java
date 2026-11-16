package datadog.trace.agent.test.utils;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;

/** Resolves the job's Test Agent port for trace producers. */
public final class TestAgentPort {
  private TestAgentPort() {}

  /** Parses {@code CI_TEST_AGENT_PORT}, using the default trace port when unset. */
  public static int parse(String value) {
    if (value == null) {
      return DEFAULT_TRACE_AGENT_PORT;
    }
    try {
      int port = Integer.parseInt(value);
      if (port >= 1 && port <= 65535) {
        return port;
      }
    } catch (NumberFormatException ignored) {
      // Report the same range requirement for malformed and out-of-range values.
    }
    throw new IllegalArgumentException("CI_TEST_AGENT_PORT must be an integer between 1 and 65535");
  }
}
