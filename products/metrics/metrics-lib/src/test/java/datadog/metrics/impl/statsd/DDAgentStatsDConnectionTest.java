package datadog.metrics.impl.statsd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DDAgentStatsDConnectionTest {

  @Test
  void connectRetryDelayDoublesUpToSixtySecondsAndNeverStops() {
    assertEquals(10, DDAgentStatsDConnection.connectRetryDelaySeconds(1));
    assertEquals(20, DDAgentStatsDConnection.connectRetryDelaySeconds(2));
    assertEquals(40, DDAgentStatsDConnection.connectRetryDelaySeconds(3));
    assertEquals(60, DDAgentStatsDConnection.connectRetryDelaySeconds(4));
    assertEquals(60, DDAgentStatsDConnection.connectRetryDelaySeconds(1_000));
    assertEquals(60, DDAgentStatsDConnection.connectRetryDelaySeconds(Integer.MAX_VALUE));
  }
}
