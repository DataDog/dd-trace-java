package datadog.metrics.api.statsd;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class StatsDClientTest {

  @Test
  void noOpConstantIsUsable() {
    assertNotNull(StatsDClient.NO_OP);
  }

  @Test
  void defaultRecordEventIsNoOp() {
    StatsDClient client = new MinimalStatsDClient();

    client.recordEvent("type", "source", "eventName", "message", "tag");
  }

  private static final class MinimalStatsDClient implements StatsDClient {
    @Override
    public void incrementCounter(String metricName, String... tags) {}

    @Override
    public void count(String metricName, long delta, String... tags) {}

    @Override
    public void gauge(String metricName, long value, String... tags) {}

    @Override
    public void gauge(String metricName, double value, String... tags) {}

    @Override
    public void histogram(String metricName, long value, String... tags) {}

    @Override
    public void histogram(String metricName, double value, String... tags) {}

    @Override
    public void distribution(String metricName, long value, String... tags) {}

    @Override
    public void distribution(String metricName, double value, String... tags) {}

    @Override
    public void serviceCheck(
        String serviceCheckName, String status, String message, String... tags) {}

    @Override
    public void error(Exception error) {}

    @Override
    public int getErrorCount() {
      return 0;
    }

    @Override
    public void close() {}
  }
}
