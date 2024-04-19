package datadog.trace.agent.jfrmetrics;

public final class JfrMetricsConfig {
  public static String JFR_METRICS_ENABLED = "experimental.jfr.metrics.enabled";
  public static boolean JFR_METRICS_ENABLED_DEFAULT = false;
  public static String JFR_METRICS_PERIOD_SECONDS = "experimental.jfr.metrics.period.seconds";
  public static int JFR_METRICS_PERIOD_SECONDS_DEFAULT = 10;
  public static String JFR_METRICS_EVENT_THRESHOLD_SECONDS =
      "experimental.jfr.metrics.event.threshold.seconds";
  public static int JFR_METRICS_EVENT_THRESHOLD_SECONDS_DEFAULT = 30;
}
