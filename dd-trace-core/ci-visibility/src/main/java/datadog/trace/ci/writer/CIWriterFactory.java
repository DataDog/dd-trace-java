package datadog.trace.ci.writer;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.core.monitor.HealthMetrics;

/**
 * Writer Factory for CI Visibility. It creates the writer with the correct configuration to be used
 * in the CI Visibility mode.
 */
public class CIWriterFactory {

  public static CIWriter createCIAgentWriter(
      final Config config,
      final SharedCommunicationObjects commObjects,
      final StatsDClient statsDClient) {
    final DDAgentApi ddAgentApi =
        new DDAgentApi(
            commObjects.okHttpClient,
            commObjects.agentUrl,
            commObjects.featuresDiscovery,
            commObjects.monitoring,
            config.isTracerMetricsEnabled());

    final DDAgentWriter agentWriter =
        DDAgentWriter.builder()
            .agentApi(ddAgentApi)
            .featureDiscovery(commObjects.featuresDiscovery)
            .prioritization(Prioritization.ENSURE_TRACE)
            .healthMetrics(new HealthMetrics(statsDClient))
            .monitoring(commObjects.monitoring)
            .build();

    return new CIAgentWriter(agentWriter);
  }

  private CIWriterFactory() {}
}
