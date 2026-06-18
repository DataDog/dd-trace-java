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

  // SpotBugs USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION: false positive, can be suppressed.
  // AgentMeter is an agent-internal holder whose Class object is not exposed to instrumented
  // application code, so external lock contention on AgentMeter.class is not a realistic risk.
  // The clean fix would be a private static final lock object, but suppression is fine here.
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
