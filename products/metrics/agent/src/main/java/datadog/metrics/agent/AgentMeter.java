package datadog.metrics.agent;

import static datadog.metrics.api.Monitoring.DISABLED;

import datadog.metrics.api.Histograms;
import datadog.metrics.api.Monitoring;
import datadog.metrics.statsd.StatsDClient;

public class AgentMeter {
  private static volatile StatsDClient statsdClient = StatsDClient.NO_OP;
  private static volatile Monitoring monitoring = DISABLED;
  private static Histograms histograms = Histograms.NO_OP;

  // StatsD client connection
  public static StatsDClient statsDClient() {
    return statsdClient;
  }

  // Health metrics monitoring
  public static Monitoring monitoring() {
    return monitoring;
  }

  // Local stored histograms
  public static Histograms histograms() {
    return histograms;
  }

  public static synchronized void registerIfAbsent(
      StatsDClient statsDClient, Monitoring monitoring, Histograms histograms) {
    if (statsDClient != null && AgentMeter.statsdClient == StatsDClient.NO_OP) {
      AgentMeter.statsdClient = statsDClient;
    }
    if (monitoring != null && monitoring != DISABLED) {
      AgentMeter.monitoring = monitoring;
    }
    if (histograms != null && histograms != Histograms.NO_OP) {
      AgentMeter.histograms = histograms;
    }
  }
}
