package datadog.trace.agent.jmxfetch;

import static datadog.trace.util.AgentThreadFactory.AgentThread.JMX_COLLECTOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static org.datadog.jmxfetch.AppConfig.ACTION_COLLECT;

import datadog.environment.SystemProperties;
import datadog.metrics.statsd.StatsDClient;
import datadog.metrics.statsd.StatsDClientManager;
import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.api.telemetry.LogCollector;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.SortedSet;
import java.util.TreeSet;
import org.datadog.jmxfetch.App;
import org.datadog.jmxfetch.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMXFetch {

  private static final Logger log = LoggerFactory.getLogger(JMXFetch.class);

  private static final String DEFAULT_CONFIG = "jmxfetch-config.yaml";
  private static final String WEBSPHERE_CONFIG = "jmxfetch-websphere-config.yaml";

  private static final int DELAY_BETWEEN_RUN_ATTEMPTS = 5000;

  public static void run(final StatsDClientManager statsDClientManager) {
    run(statsDClientManager, Config.get());
  }

  // This is used by tests
  private static void run(final StatsDClientManager statsDClientManager, final Config config) {
    if (!config.isJmxFetchEnabled()) {
      log.debug("JMXFetch is disabled");
      return;
    }

    if (!log.isDebugEnabled()
        && SystemProperties.get("org.slf4j.simpleLogger.log.org.datadog.jmxfetch") == null) {
      // Reduce noisiness of jmxfetch logging.
      SystemProperties.set("org.slf4j.simpleLogger.log.org.datadog.jmxfetch", "warn");
    }

    final String jmxFetchConfigDir = config.getJmxFetchConfigDir();
    final List<String> jmxFetchConfigs = config.getJmxFetchConfigs();
    final List<String> internalMetricsConfigs = getInternalMetricFiles();
    final List<String> metricsConfigs = config.getJmxFetchMetricsConfigs();
    final Integer checkPeriod = config.getJmxFetchCheckPeriod();
    final Integer refreshBeansPeriod = config.getJmxFetchRefreshBeansPeriod();
    final Integer initialRefreshBeansPeriod = config.getJmxFetchInitialRefreshBeansPeriod();
    final Map<String, String> globalTags = config.getMergedJmxTags();

    String host = config.getJmxFetchStatsdHost();
    Integer port = config.getJmxFetchStatsdPort();
    String namedPipe = config.getDogStatsDNamedPipe();

    if (log.isDebugEnabled()) {
      String statsDConnectionString;
      if (namedPipe == null) {
        statsDConnectionString =
            "statsd:"
                + (null != host ? host : "<auto-detect>")
                + (null != port && port > 0 ? ":" + port : "");
      } else {
        statsDConnectionString = "statsd:" + namedPipe;
      }

      log.debug(
          "JMXFetch config: {} {} {} {} {} {} {} {} {}",
          jmxFetchConfigDir,
          jmxFetchConfigs,
          internalMetricsConfigs,
          metricsConfigs,
          checkPeriod,
          initialRefreshBeansPeriod,
          refreshBeansPeriod,
          globalTags,
          statsDConnectionString);
    }

    final StatsDClient statsd = statsDClientManager.statsDClient(host, port, namedPipe, null, null);
    final AgentStatsdReporter reporter = new AgentStatsdReporter(statsd);

    TracerFlare.addReporter(reporter);
    final List<String> defaultConfigs = new ArrayList<>();
    defaultConfigs.add(DEFAULT_CONFIG);
    if (config.isJmxFetchIntegrationEnabled(Collections.singletonList("websphere"), false)) {
      defaultConfigs.add(WEBSPHERE_CONFIG);
    }

    final AppConfig.AppConfigBuilder configBuilder =
        AppConfig.builder()
            .action(Collections.singletonList(ACTION_COLLECT))
            // App should be run as daemon otherwise CLI apps would not exit once main method exits.
            .daemon(true)
            .embedded(true)
            .confdDirectory(jmxFetchConfigDir)
            .yamlFileList(jmxFetchConfigs)
            .targetDirectInstances(true)
            .instanceConfigResources(defaultConfigs)
            .metricConfigResources(internalMetricsConfigs)
            .metricConfigFiles(metricsConfigs)
            .initialRefreshBeansPeriod(initialRefreshBeansPeriod)
            .refreshBeansPeriod(refreshBeansPeriod)
            .globalTags(globalTags)
            .reporter(reporter)
            .connectionFactory(new AgentConnectionFactory());

    if (config.isJmxFetchMultipleRuntimeServicesEnabled()) {
      ServiceNameCollectingTraceInterceptor serviceNameProvider =
          ServiceNameCollectingTraceInterceptor.INSTANCE;
      GlobalTracer.get().addTraceInterceptor(serviceNameProvider);

      configBuilder.serviceNameProvider(serviceNameProvider);
    }

    if (checkPeriod != null) {
      configBuilder.checkPeriod(checkPeriod);
    }

    final AppConfig appConfig = configBuilder.build();

    final Thread thread =
        newAgentThread(
            JMX_COLLECTOR,
            new Runnable() {
              @Override
              public void run() {
                App app = new App(appConfig);
                while (true) {
                  // check in case dynamic-config has temporarily disabled JMXFetch
                  if (!appConfig.getExitWatcher().shouldExit()) {
                    try {
                      final int result = app.run();
                      if (result != 0) {
                        log.warn("jmx collector exited with error code: {}", result);
                      }
                    } catch (final Exception e) {
                      String message = e.getMessage();
                      boolean ignoredException =
                          message != null && message.startsWith("Shutdown in progress");
                      if (!ignoredException) {
                        log.warn("Exception in jmx collector thread", e);
                      }
                    }
                  }
                  // always wait before next attempt
                  try {
                    Thread.sleep(DELAY_BETWEEN_RUN_ATTEMPTS);
                  } catch (final InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                }
              }
            });
    thread.setContextClassLoader(JMXFetch.class.getClassLoader());
    thread.start();
  }

  @SuppressForbidden
  private static List<String> getInternalMetricFiles() {
    try (final InputStream metricConfigsStream =
        JMXFetch.class.getResourceAsStream("metricconfigs.txt")) {
      if (metricConfigsStream == null) {
        log.debug("metricconfigs not found. returning empty set");
        return Collections.emptyList();
      }
      log.debug("reading found metricconfigs");
      Scanner scanner = new Scanner(metricConfigsStream);
      scanner.useDelimiter("\n");
      final List<String> result = new ArrayList<>();
      final SortedSet<String> integrationName = new TreeSet<>();
      while (scanner.hasNext()) {
        String config = scanner.next();
        integrationName.clear();
        integrationName.add(config.replace(".yaml", ""));

        if (!Config.get().isJmxFetchIntegrationEnabled(integrationName, false)) {
          log.debug(
              "skipping metric config `{}` because integration {} is disabled",
              config,
              integrationName);
        } else {
          final URL resource = JMXFetch.class.getResource("metricconfigs/" + config);
          if (resource == null) {
            log.debug(
                LogCollector.SEND_TELEMETRY, "metric config `{}` not found. skipping", config);
            continue;
          }
          log.debug("adding metric config `{}`", config);

          // jar!/ means a file internal to a jar, only add the part after if it exists
          final String path = resource.getPath();
          final int filenameIndex = path.indexOf("jar!/");
          if (filenameIndex != -1) {
            result.add(path.substring(filenameIndex + 5));
          } else {
            result.add(path.substring(1));
          }
        }
      }
      return result;
    } catch (final IOException e) {
      log.debug("error reading metricconfigs. returning empty set", e);
      return Collections.emptyList();
    }
  }

  private static String getLogLocation() {
    return SystemProperties.getOrDefault("org.slf4j.simpleLogger.logFile", "System.err");
  }

  private static String getLogLevel() {
    return SystemProperties.getOrDefault("org.slf4j.simpleLogger.defaultLogLevel", "info")
        .toUpperCase();
  }
}
