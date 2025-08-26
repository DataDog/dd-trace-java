package datadog.config;

import static datadog.config.util.Strings.toEnvVar;

import datadog.config.telemetry.OtelEnvMetricCollector;
import datadog.config.util.Strings;
import datadog.environment.EnvironmentVariables;
import datadog.environment.SystemProperties;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.config.TracerConfig;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maps OpenTelemetry system properties and environment variables to their Datadog equivalents. */
final class OtelEnvironmentConfigSource extends ConfigProvider.Source {
  private static final Logger log = LoggerFactory.getLogger(OtelEnvironmentConfigSource.class);

  private final boolean enabled;

  private final Map<String, String> otelEnvironment = new HashMap<>();

  private final Properties otelConfigFile = loadOtelConfigFile();

  private final Properties datadogConfigFile;
  private static final OtelEnvMetricCollector otelEnvMetricCollector =
      OtelEnvMetricCollector.getInstance();

  @Override
  protected String get(String key) {
    if (!enabled) {
      return null;
    }

    String value = otelEnvironment.get(key);
    if (null == value && key.startsWith("otel.")) {
      value = getOtelProperty(key);
    }
    return value;
  }

  @Override
  public ConfigOrigin origin() {
    return ConfigOrigin.ENV;
  }

  OtelEnvironmentConfigSource() {
    this(null);
  }

  OtelEnvironmentConfigSource(Properties datadogConfigFile) {
    this.enabled = traceOtelEnabled();
    this.datadogConfigFile = datadogConfigFile;

    if (enabled) {
      setupOteEnvironment();
    }
  }

  private void setupOteEnvironment() {

    // only applies when OTEL is enabled by default (otherwise TRACE_OTEL_ENABLED takes precedence)
    String sdkDisabled =
        getOtelProperty("otel.sdk.disabled", "dd." + TraceInstrumentationConfig.TRACE_OTEL_ENABLED);
    if ("true".equalsIgnoreCase(sdkDisabled)) {
      capture(TraceInstrumentationConfig.TRACE_OTEL_ENABLED, "false");
      return;
    }

    String serviceName = getOtelProperty("otel.service.name", "dd." + GeneralConfig.SERVICE_NAME);
    String logLevel = getOtelProperty("otel.log.level", "dd." + GeneralConfig.LOG_LEVEL);
    String propagators =
        getOtelProperty("otel.propagators", "dd." + TracerConfig.TRACE_PROPAGATION_STYLE);
    String tracesSampler =
        getOtelProperty("otel.traces.sampler", "dd." + TracerConfig.TRACE_SAMPLE_RATE);
    String resourceAttributes =
        getOtelProperty("otel.resource.attributes", "dd." + GeneralConfig.TAGS);
    String requestHeaders =
        getOtelHeaders("request-headers", "dd." + TracerConfig.REQUEST_HEADER_TAGS);
    String responseHeaders =
        getOtelHeaders("response-headers", "dd." + TracerConfig.RESPONSE_HEADER_TAGS);
    String extensions =
        getOtelProperty(
            "otel.javaagent.extensions", "dd." + TraceInstrumentationConfig.TRACE_EXTENSIONS_PATH);

    if (null != resourceAttributes) {
      Map<String, String> attributeMap = parseOtelMap(resourceAttributes);
      capture(GeneralConfig.SERVICE_NAME, attributeMap.remove("service.name"));
      capture(GeneralConfig.VERSION, attributeMap.remove("service.version"));
      capture(GeneralConfig.ENV, attributeMap.remove("deployment.environment"));
      capture(GeneralConfig.TAGS, renderDatadogMap(attributeMap, 10));
    }

    capture(GeneralConfig.LOG_LEVEL, logLevel);
    capture(GeneralConfig.SERVICE_NAME, serviceName);
    capture(TracerConfig.TRACE_PROPAGATION_STYLE, mapPropagationStyle(propagators));
    capture(TracerConfig.TRACE_SAMPLE_RATE, mapSampleRate(tracesSampler));

    capture(TraceInstrumentationConfig.TRACE_ENABLED, mapDataCollection("traces"));
    capture(GeneralConfig.RUNTIME_METRICS_ENABLED, mapDataCollection("metrics"));
    mapDataCollection("logs"); // check setting, but no need to capture it

    capture(
        TracerConfig.REQUEST_HEADER_TAGS, mapHeaderTags("http.request.header.", requestHeaders));
    capture(
        TracerConfig.RESPONSE_HEADER_TAGS, mapHeaderTags("http.response.header.", responseHeaders));

    capture(TraceInstrumentationConfig.TRACE_EXTENSIONS_PATH, extensions);
  }

  private boolean traceOtelEnabled() {
    String enabled = getDatadogProperty("dd." + TraceInstrumentationConfig.TRACE_OTEL_ENABLED);
    if (null != enabled) {
      return Boolean.parseBoolean(enabled);
    } else {
      return ConfigDefaults.DEFAULT_TRACE_OTEL_ENABLED;
    }
  }

  /**
   * Gets an OpenTelemetry property.
   *
   * <p>Checks system properties, environment variables, and the optional OpenTelemetry config file.
   * If the equivalent Datadog property is also set then log a warning and return {@code null}.
   */
  private String getOtelProperty(String otelSysProp, String ddSysProp) {
    String otelValue = getOtelProperty(otelSysProp);
    if (null == otelValue) {
      return null;
    }
    String ddValue = getDatadogProperty(ddSysProp);
    if (null != ddValue) {
      String otelEnvVar = toEnvVar(otelSysProp);
      log.warn("Both {} and {} are set, ignoring {}", toEnvVar(ddSysProp), otelEnvVar, otelEnvVar);
      otelEnvMetricCollector.setHidingOtelEnvVarMetric(
          Strings.toEnvVarLowerCase(otelSysProp), Strings.toEnvVarLowerCase(ddSysProp));
      return null;
    }
    return otelValue;
  }

  /**
   * Gets an OpenTelemetry property.
   *
   * <p>Checks system properties, environment variables, and the optional OpenTelemetry config file.
   */
  private String getOtelProperty(String sysProp) {
    String value = getProperty(sysProp);
    if (null == value && null != otelConfigFile) {
      value = otelConfigFile.getProperty(sysProp);
    }
    return value;
  }

  /**
   * Gets a Datadog property.
   *
   * <p>Checks system properties, environment variables, and the optional Datadog config file.
   */
  private String getDatadogProperty(String sysProp) {
    String value = getProperty(sysProp);
    if (null == value && null != datadogConfigFile) {
      value = datadogConfigFile.getProperty(sysProp);
    }
    return value;
  }

  /**
   * Gets a general property.
   *
   * <p>Checks system properties and environment variables.
   */
  private static String getProperty(String sysProp) {
    String value = SystemProperties.get(sysProp);
    if (null == value) {
      value = EnvironmentVariables.get(toEnvVar(sysProp));
    }
    return value;
  }

  /** Captures a mapped OpenTelemetry property. */
  private void capture(String key, String value) {
    if (null != value) {
      otelEnvironment.put(key, value);
    }
  }

  /** Loads the optional OpenTelemetry configuration file. */
  private static Properties loadOtelConfigFile() {
    String path = getProperty("otel.javaagent.configuration-file");
    if (null != path && !path.isEmpty()) {
      // Inflate '~' prefix as home folder
      String home;
      if (path.charAt(0) == '~' && (home = SystemProperties.get("user.home")) != null) {
        path = home + path.substring(1);
      }
      File file = new File(path);
      if (file.exists()) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
          Properties properties = new Properties();
          properties.load(in);
          return properties;
        } catch (Throwable e) {
          log.warn("Problem reading OTEL_JAVAAGENT_CONFIGURATION_FILE {} - {}", path, e.toString());
        }
      }
    }
    return null;
  }

  /** Parses a comma-separated list of items. */
  private static List<String> parseOtelList(String value) {
    List<String> list = new ArrayList<>();
    int start = 0;
    while (start < value.length()) {
      int end = value.indexOf(',', start);
      if (end < 0) {
        end = value.length();
      }
      if (end > start) {
        list.add(value.substring(start, end));
      }
      start = end + 1;
    }
    return list;
  }

  /** Parses a comma-separated list of key=value entries. */
  private static Map<String, String> parseOtelMap(String value) {
    Map<String, String> map = new LinkedHashMap<>();
    int start = 0;
    while (start < value.length()) {
      int end = value.indexOf(',', start);
      if (end < 0) {
        end = value.length();
      }
      if (end > start) {
        String entry = value.substring(start, end);
        int eq = entry.indexOf('=');
        if (eq > 0) {
          map.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
      }
      start = end + 1;
    }
    return map;
  }

  /** Renders the map as a comma-separated list of key:value entries. */
  private static String renderDatadogMap(Map<String, String> map, int maxEntries) {
    StringBuilder buf = new StringBuilder();

    int entries = 0;
    for (Map.Entry<String, String> entry : map.entrySet()) {
      buf.append(entry.getKey()).append(':').append(entry.getValue()).append(',');
      if (++entries >= maxEntries) {
        break;
      }
    }

    // remove trailing comma, mapping empty conversion to null
    return buf.length() > 1 ? buf.substring(0, buf.length() - 1) : null;
  }

  /** Maps OpenTelemetry propagators to a list of accepted propagation styles. */
  private static String mapPropagationStyle(String propagators) {
    if (null == propagators) {
      return null;
    }

    StringBuilder buf = new StringBuilder();
    for (String style : parseOtelList(propagators)) {
      if ("b3".equalsIgnoreCase(style)) {
        buf.append("b3single,"); // force use of OpenTelemetry alias
      } else {
        try {
          buf.append(TracePropagationStyle.valueOfDisplayName(style)).append(',');
        } catch (IllegalArgumentException e) {
          log.warn("OTEL_PROPAGATORS={} is not supported", style);
          otelEnvMetricCollector.setInvalidOtelEnvVarMetric(
              "otel_propagators", "dd_trace_propagation_style");
        }
      }
    }

    // remove trailing comma, mapping empty conversion to null
    return buf.length() > 1 ? buf.substring(0, buf.length() - 1) : null;
  }

  /** Maps known OpenTelemetry samplers to a trace sample rate. */
  private String mapSampleRate(String tracesSampler) {
    if (null == tracesSampler) {
      return null;
    }

    if ("traceidratio".equalsIgnoreCase(tracesSampler)
        || "always_on".equalsIgnoreCase(tracesSampler)
        || "always_off".equalsIgnoreCase(tracesSampler)) {
      log.warn(
          "OTEL_TRACES_SAMPLER changed from {} to parentbased_{}; only parent based sampling is supported.",
          tracesSampler,
          tracesSampler);
      tracesSampler = "parentbased_" + tracesSampler;
    }

    if ("parentbased_traceidratio".equalsIgnoreCase(tracesSampler)) {
      return getOtelProperty("otel.traces.sampler.arg");
    } else if ("parentbased_always_on".equalsIgnoreCase(tracesSampler)) {
      return "1.0";
    } else if ("parentbased_always_off".equalsIgnoreCase(tracesSampler)) {
      return "0.0";
    }

    log.warn("OTEL_TRACES_SAMPLER={} is not supported", tracesSampler);
    otelEnvMetricCollector.setInvalidOtelEnvVarMetric(
        "otel_traces_sampler", "dd_trace_sample_rate");
    return null;
  }

  /** Maps an OpenTelemetry exporter setting to the equivalent Datadog collection setting. */
  private String mapDataCollection(String type) {
    String exporter = getOtelProperty("otel." + type + ".exporter");
    if (null == exporter) {
      return null;
    }

    if ("none".equalsIgnoreCase(exporter)) {
      return "false"; // currently we only accept "none" which maps to disable data collection
    }

    log.warn("OTEL_{}_EXPORTER={} is not supported", type, exporter.toUpperCase(Locale.ROOT));
    otelEnvMetricCollector.setUnsupportedOtelEnvVarMetric("otel_" + type + "_exporter");

    return null;
  }

  /** Merges the OpenTelemetry client and server headers to capture into a single list. */
  private String getOtelHeaders(String otelSuffix, String ddSysProp) {
    String clientTags =
        getOtelProperty("otel.instrumentation.http.client.capture-" + otelSuffix, ddSysProp);
    String serverTags =
        getOtelProperty("otel.instrumentation.http.server.capture-" + otelSuffix, ddSysProp);
    if (null == clientTags) {
      return serverTags;
    } else if (null == serverTags) {
      return clientTags;
    } else {
      return clientTags + ',' + serverTags;
    }
  }

  /**
   * Maps OpenTelemetry list of headers to Datadog header tags, preserving the expected tag name.
   */
  private static String mapHeaderTags(String tagPrefix, String headers) {
    if (null == headers) {
      return null;
    }

    StringBuilder buf = new StringBuilder();
    for (String header : parseOtelList(headers)) {
      buf.append(header).append(':').append(tagPrefix).append(header).append(',');
    }

    // remove trailing comma, mapping empty conversion to null
    return buf.length() > 1 ? buf.substring(0, buf.length() - 1) : null;
  }
}
