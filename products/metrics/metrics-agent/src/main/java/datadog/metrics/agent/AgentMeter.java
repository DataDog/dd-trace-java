package datadog.metrics.agent;

import static datadog.metrics.api.Monitoring.DISABLED;

import datadog.metrics.api.Histograms;
import datadog.metrics.api.Monitoring;
import datadog.metrics.api.statsd.StatsDClient;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class AgentMeter {
  private static volatile StatsDClient statsdClient = StatsDClient.NO_OP;
  private static volatile Monitoring monitoring = DISABLED;

  // StatsD client connection
  public static StatsDClient statsDClient() {
    return statsdClient;
  }

  // Health metrics monitoring
  public static Monitoring monitoring() {
    return monitoring;
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal holder; AgentMeter.class is not exposed to instrumented app code")
  public static synchronized void registerIfAbsent(
      StatsDClient statsDClient, Monitoring monitoring, Histograms.Factory historgramFactory) {
    if (statsDClient != null && AgentMeter.statsdClient == StatsDClient.NO_OP) {
      AgentMeter.statsdClient = statsDClient;
    }
    if (monitoring != null && monitoring != DISABLED) {
      AgentMeter.monitoring = monitoring;
    }
    Histograms.register(historgramFactory);
  }
}
