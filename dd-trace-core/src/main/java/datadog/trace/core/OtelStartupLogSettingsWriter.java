package datadog.trace.core;

import static datadog.trace.api.ConfigDefaults.DEFAULT_OTLP_GRPC_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_OTLP_HTTP_PORT;

import com.squareup.moshi.JsonWriter;
import datadog.trace.api.Config;
import datadog.trace.api.TracePropagationBehaviorExtract;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.common.sampling.AllSampler;
import datadog.trace.common.sampling.ApmTracingDisabledSampler;
import datadog.trace.common.sampling.AsmStandaloneSampler;
import datadog.trace.common.sampling.ForcePrioritySampler;
import datadog.trace.common.sampling.ParentBasedAlwaysOnSampler;
import datadog.trace.common.sampling.RateByServiceTraceSampler;
import datadog.trace.common.sampling.RuleBasedTraceSampler;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.core.otlp.common.OtlpResourceAttributes;
import datadog.trace.util.Strings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import org.slf4j.Logger;

/**
 * Writes the effective OpenTelemetry settings to the startup configuration log of {@link
 * StatusLogger}, each named after its environment variable.
 *
 * <p>The exporter settings of a signal are only written when that signal is exported over OTLP.
 * Header values are redacted, and endpoints drop their user info, query and fragment, which may
 * hold credentials.
 */
final class OtelStartupLogSettingsWriter {
  static final String REDACTED = "<redacted>";

  private OtelStartupLogSettingsWriter() {}

  /** {@code log} writes the startup log, so its level is the one reported. */
  static void write(JsonWriter writer, Config config, Sampler sampler, Logger log)
      throws IOException {
    boolean tracesOtlp = config.isTraceEnabled() && config.isOtlpTracesExportEnabled();
    boolean metricsOtlp = config.isOtlpMetricsExportEnabled();
    boolean logsOtlp = config.isOtlpLogsExportEnabled();

    writer.name("DD_TRACE_OTEL_ENABLED").value(config.isTraceOtelEnabled());
    writer.name("DD_METRICS_OTEL_ENABLED").value(config.isMetricsOtelEnabled());
    writer.name("DD_LOGS_OTEL_ENABLED").value(config.isLogsOtelEnabled());
    writer.name("OTEL_TRACES_EXPORTER").value(tracesOtlp ? "otlp" : "none");
    writer.name("OTEL_LOGS_EXPORTER").value(logsOtlp ? "otlp" : "none");
    writer.name("OTEL_LOG_LEVEL").value(logLevel(log));
    writer.name("OTEL_SERVICE_NAME").value(config.getServiceName());
    writer.name("OTEL_RESOURCE_ATTRIBUTES");
    StatusLogger.writeMap(writer, OtlpResourceAttributes.resourceAttributes(config));
    // omitted when no single list describes both injection and extraction
    writer.name("OTEL_PROPAGATORS").value(propagators(config));
    writeSampler(writer, sampler);

    if (tracesOtlp) {
      writer
          .name("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
          .value(sanitizeEndpoint(config.getOtlpTracesEndpoint()));
      writer
          .name("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL")
          .value(protocolName(config.getOtlpTracesProtocol()));
      writer.name("OTEL_EXPORTER_OTLP_TRACES_HEADERS");
      writeRedactedHeaders(writer, config.getOtlpTracesHeaders().keySet());
    }
    if (metricsOtlp) {
      writer
          .name("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT")
          .value(sanitizeEndpoint(config.getOtlpMetricsEndpoint()));
      writer
          .name("OTEL_EXPORTER_OTLP_METRICS_PROTOCOL")
          .value(protocolName(config.getOtlpMetricsProtocol()));
      writer.name("OTEL_EXPORTER_OTLP_METRICS_HEADERS");
      writeRedactedHeaders(writer, config.getOtlpMetricsHeaders().keySet());
      // OTLP span metrics are always delta and flushed with the trace stats
      if (config.isMetricsOtelEnabled() && config.isMetricsOtlpExporterEnabled()) {
        writer.name("OTEL_METRIC_EXPORT_INTERVAL").value(config.getMetricsOtelInterval());
        writer
            .name("OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE")
            .value(config.getOtlpMetricsTemporalityPreference().name().toLowerCase(Locale.ROOT));
      }
    }
    if (logsOtlp) {
      writer
          .name("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT")
          .value(sanitizeEndpoint(config.getOtlpLogsEndpoint()));
      writer
          .name("OTEL_EXPORTER_OTLP_LOGS_PROTOCOL")
          .value(protocolName(config.getOtlpLogsProtocol()));
      writer.name("OTEL_EXPORTER_OTLP_LOGS_HEADERS");
      writeRedactedHeaders(writer, config.getOtlpLogsHeaders().keySet());
      writer.name("OTEL_BLRP_SCHEDULE_DELAY").value(config.getLogsOtelInterval());
      writer.name("OTEL_BLRP_EXPORT_TIMEOUT").value(config.getLogsOtelTimeout());
      writer.name("OTEL_BLRP_MAX_QUEUE_SIZE").value(config.getLogsOtelQueueSize());
      writer.name("OTEL_BLRP_MAX_EXPORT_BATCH_SIZE").value(config.getLogsOtelBatchSize());
    }
    if (tracesOtlp || metricsOtlp || logsOtlp) {
      writeGeneralExporter(writer, config);
    }
  }

  /**
   * Writes the general OTLP settings, which apply to the signals without their own. They default to
   * the OTLP port of the Datadog Agent, like the signal endpoints.
   */
  private static void writeGeneralExporter(JsonWriter writer, Config config) throws IOException {
    ConfigProvider configProvider = config.configProvider();
    OtlpConfig.Protocol protocol =
        parseProtocol(configProvider.getOtelSetting("otel.exporter.otlp.protocol"));
    String endpoint = configProvider.getOtelSetting("otel.exporter.otlp.endpoint");
    if (endpoint == null) {
      String port =
          protocol == OtlpConfig.Protocol.GRPC ? DEFAULT_OTLP_GRPC_PORT : DEFAULT_OTLP_HTTP_PORT;
      endpoint = "http://" + config.getAgentHost() + ':' + port;
    }
    writer.name("OTEL_EXPORTER_OTLP_ENDPOINT").value(sanitizeEndpoint(endpoint));
    writer.name("OTEL_EXPORTER_OTLP_PROTOCOL").value(protocolName(protocol));
    writer.name("OTEL_EXPORTER_OTLP_HEADERS");
    writeRedactedHeaders(
        writer, headerNames(configProvider.getOtelSetting("otel.exporter.otlp.headers")));
  }

  private static void writeRedactedHeaders(JsonWriter writer, Collection<String> headerNames)
      throws IOException {
    writer.beginObject();
    for (String headerName : new TreeSet<>(headerNames)) {
      writer.name(headerName).value(REDACTED);
    }
    writer.endObject();
  }

  /**
   * Writes the OpenTelemetry sampler equivalent to the root sampler. Propagated sampling decisions
   * are never resampled, so every equivalent is parent based. Datadog sampling without an
   * equivalent is named {@code datadog_*}, as in other Datadog SDKs.
   */
  private static void writeSampler(JsonWriter writer, Sampler sampler) throws IOException {
    Double ratio = null;
    String name;
    if (sampler instanceof RuleBasedTraceSampler) {
      RuleBasedTraceSampler<?> ruleBasedSampler = (RuleBasedTraceSampler<?>) sampler;
      Double rate = ruleBasedSampler.getFixedSampleRate();
      if (!ruleBasedSampler.hasRules()) {
        name = "datadog_agent_rates";
      } else if (rate == null) {
        name = "datadog_custom_rules";
      } else if (rate <= 0) {
        name = "parentbased_always_off";
      } else if (rate >= 1) {
        name = "parentbased_always_on";
      } else {
        name = "parentbased_traceidratio";
        ratio = rate;
      }
    } else if (sampler instanceof ForcePrioritySampler) {
      name =
          ((ForcePrioritySampler) sampler).getSamplingPriority() > 0
              ? "parentbased_always_on"
              : "parentbased_always_off";
    } else if (sampler instanceof ParentBasedAlwaysOnSampler) {
      name = "parentbased_always_on";
    } else if (sampler instanceof RateByServiceTraceSampler) {
      name = "datadog_agent_rates";
    } else if (sampler instanceof AllSampler) {
      name = "always_on";
    } else if (sampler instanceof AsmStandaloneSampler
        || sampler instanceof ApmTracingDisabledSampler) {
      name = "datadog_apm_tracing_disabled";
    } else {
      name = "datadog_custom_sampler";
    }
    writer.name("OTEL_TRACES_SAMPLER").value(name);
    // omitted for the samplers without a ratio
    writer.name("OTEL_TRACES_SAMPLER_ARG").value(ratio);
  }

  /**
   * Returns the OpenTelemetry propagators, or {@code null} when injection and extraction differ or
   * extraction is ignored, since no single list then describes them.
   */
  @Nullable
  static String propagators(Config config) {
    Set<TracePropagationStyle> inject = config.getTracePropagationStylesToInject();
    if (config.getTracePropagationBehaviorExtract() == TracePropagationBehaviorExtract.IGNORE
        || !new ArrayList<>(inject)
            .equals(new ArrayList<>(config.getTracePropagationStylesToExtract()))) {
      return null;
    }
    StringJoiner propagators = new StringJoiner(",");
    for (TracePropagationStyle style : inject) {
      propagators.add(propagatorName(style));
    }
    return propagators.toString();
  }

  private static String propagatorName(TracePropagationStyle style) {
    switch (style) {
      case B3SINGLE:
        return "b3";
      case B3MULTI:
        return "b3multi";
      default:
        return style.name().toLowerCase(Locale.ROOT);
    }
  }

  private static String logLevel(Logger log) {
    if (log.isTraceEnabled()) {
      return "trace";
    } else if (log.isDebugEnabled()) {
      return "debug";
    } else if (log.isInfoEnabled()) {
      return "info";
    } else if (log.isWarnEnabled()) {
      return "warn";
    } else if (log.isErrorEnabled()) {
      return "error";
    }
    return "off";
  }

  private static String protocolName(OtlpConfig.Protocol protocol) {
    return protocol.name().toLowerCase(Locale.ROOT).replace('_', '/');
  }

  /** Parses an OTLP protocol the way the signal settings are, defaulting to HTTP/protobuf. */
  private static OtlpConfig.Protocol parseProtocol(@Nullable String protocol) {
    if (protocol != null) {
      try {
        return OtlpConfig.Protocol.valueOf(protocol.replace('/', '_').toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        // invalid protocols fall back to the default, like the signal settings
      }
    }
    return OtlpConfig.Protocol.HTTP_PROTOBUF;
  }

  /** Returns the names of OTLP headers listed as comma-separated {@code name=value} entries. */
  private static Collection<String> headerNames(@Nullable String headers) {
    Collection<String> names = new ArrayList<>();
    if (headers != null) {
      for (CharSequence subSequence : Strings.split(headers, ',')) {
        String entry = subSequence.toString();
        int separator = entry.indexOf('=');
        if (separator > 0) {
          String name = entry.substring(0, separator).trim();
          if (!name.isEmpty()) {
            names.add(name);
          }
        }
      }
    }
    return names;
  }

  /**
   * Drops the user info, query and fragment of an endpoint, which may hold credentials. The
   * endpoint is parsed like the OTLP senders parse it, so they agree on where the credentials are,
   * and it is redacted when it cannot be parsed. An endpoint without credentials is returned as
   * configured, since parsing normalizes it.
   */
  static String sanitizeEndpoint(String endpoint) {
    if (endpoint.startsWith("unix://")) {
      return endpoint; // a socket path
    }
    HttpUrl url = HttpUrl.parse(endpoint);
    if (url == null) {
      return REDACTED;
    }
    if (url.encodedUsername().isEmpty()
        && url.encodedPassword().isEmpty()
        && url.encodedQuery() == null
        && url.encodedFragment() == null) {
      return endpoint;
    }
    return url.newBuilder().username("").password("").query(null).fragment(null).build().toString();
  }
}
