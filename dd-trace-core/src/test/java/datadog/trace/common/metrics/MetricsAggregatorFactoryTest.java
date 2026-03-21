package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.core.monitor.HealthMetrics;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MetricsAggregatorFactoryTest {

  @Test
  void whenMetricsDisabledNoOpAggregatorCreated() {
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.isTracerMetricsEnabled()).thenReturn(false);
    SharedCommunicationObjects sco = new SharedCommunicationObjects();
    sco.agentUrl = HttpUrl.parse("http://localhost:8126");

    MetricsAggregator aggregator =
        MetricsAggregatorFactory.createMetricsAggregator(config, sco, HealthMetrics.NO_OP);
    assertTrue(aggregator instanceof NoOpMetricsAggregator);
  }

  @Test
  void whenMetricsEnabledConflatingAggregatorCreated() {
    Config config = Mockito.spy(Config.get());
    Mockito.when(config.isTracerMetricsEnabled()).thenReturn(true);
    SharedCommunicationObjects sco = new SharedCommunicationObjects();
    sco.agentUrl = HttpUrl.parse("http://localhost:8126");

    MetricsAggregator aggregator =
        MetricsAggregatorFactory.createMetricsAggregator(config, sco, HealthMetrics.NO_OP);
    assertTrue(aggregator instanceof ConflatingMetricsAggregator);
  }
}
