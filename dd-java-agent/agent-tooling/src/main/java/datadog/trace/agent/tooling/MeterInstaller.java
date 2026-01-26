package datadog.trace.agent.tooling;

import static datadog.metrics.impl.statsd.DDAgentStatsDClientManager.statsDClientManager;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.Monitoring;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.Config;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.core.DDTraceCoreInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MeterInstaller {
  private static final String LANG_STATSD_TAG = "lang";
  private static final String LANG_VERSION_STATSD_TAG = "lang_version";
  private static final String LANG_INTERPRETER_STATSD_TAG = "lang_interpreter";
  private static final String LANG_INTERPRETER_VENDOR_STATSD_TAG = "lang_interpreter_vendor";
  private static final String TRACER_VERSION_STATSD_TAG = "tracer_version";

  public static void installMeter() {
    Config config = Config.get();
    StatsDClient statsDClient = createStatsDClient(config);
    Monitoring monitoring =
        config.isHealthMetricsEnabled()
            ? new MonitoringImpl(statsDClient, 10, SECONDS)
            : MonitoringImpl.DISABLED;
    AgentMeter.registerIfAbsent(statsDClient, monitoring, DDSketchHistograms.FACTORY);
  }

  private static StatsDClient createStatsDClient(Config config) {
    if (!config.isHealthMetricsEnabled()) {
      return StatsDClient.NO_OP;
    } else {
      String host = config.getHealthMetricsStatsdHost();
      if (host == null) {
        host = config.getJmxFetchStatsdHost();
      }
      Integer port = config.getHealthMetricsStatsdPort();
      if (port == null) {
        port = config.getJmxFetchStatsdPort();
      }

      return statsDClientManager()
          .statsDClient(
              host,
              port,
              config.getDogStatsDNamedPipe(),
              // use replace to stop string being changed to 'ddtrot.dd.tracer' in dd-trace-ot
              "datadog:tracer".replace(':', '.'),
              generateConstantTags(config));
    }
  }

  private static String[] generateConstantTags(final Config config) {
    final List<String> constantTags = new ArrayList<>();

    constantTags.add(statsdTag(LANG_STATSD_TAG, "java"));
    constantTags.add(statsdTag(LANG_VERSION_STATSD_TAG, DDTraceCoreInfo.JAVA_VERSION));
    constantTags.add(statsdTag(LANG_INTERPRETER_STATSD_TAG, DDTraceCoreInfo.JAVA_VM_NAME));
    constantTags.add(statsdTag(LANG_INTERPRETER_VENDOR_STATSD_TAG, DDTraceCoreInfo.JAVA_VM_VENDOR));
    constantTags.add(statsdTag(TRACER_VERSION_STATSD_TAG, DDTraceCoreInfo.VERSION));
    constantTags.add(statsdTag("service", config.getServiceName()));

    final Map<String, String> mergedSpanTags = config.getMergedSpanTags();
    final String version = mergedSpanTags.get(GeneralConfig.VERSION);
    if (version != null && !version.isEmpty()) {
      constantTags.add(statsdTag("version", version));
    }

    final String env = mergedSpanTags.get(GeneralConfig.ENV);
    if (env != null && !env.isEmpty()) {
      constantTags.add(statsdTag("env", env));
    }

    return constantTags.toArray(new String[0]);
  }

  private static String statsdTag(final String tagPrefix, final String tagValue) {
    return tagPrefix + ":" + tagValue;
  }
}
