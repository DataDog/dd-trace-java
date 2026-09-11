package datadog.metrics.api.statsd;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class StatsDClientManagerTest {

  @Test
  void defaultFiveArgOverloadDelegatesWithAggregationEnabled() {
    StatsDClient client = StatsDClient.NO_OP;
    RecordingStatsDClientManager manager = new RecordingStatsDClientManager(client);

    StatsDClient result = manager.statsDClient("host", 8125, null, "ns", new String[] {"tag"});

    assertSame(client, result);
    assertSame(Boolean.TRUE, manager.lastUseAggregation);
  }

  private static final class RecordingStatsDClientManager implements StatsDClientManager {
    private final StatsDClient client;
    private Boolean lastUseAggregation;

    RecordingStatsDClientManager(StatsDClient client) {
      this.client = client;
    }

    @Override
    public StatsDClient statsDClient(
        String host,
        Integer port,
        String namedPipe,
        String namespace,
        String[] constantTags,
        boolean useAggregation) {
      this.lastUseAggregation = useAggregation;
      return client;
    }
  }
}
