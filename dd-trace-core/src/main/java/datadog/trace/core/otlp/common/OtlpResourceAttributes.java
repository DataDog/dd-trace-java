package datadog.trace.core.otlp.common;

import static datadog.communication.ddagent.TracerVersion.TRACER_VERSION;
import static java.util.Arrays.asList;

import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Enumerates the resource attributes shared by the proto and JSON "resource.proto" encoders. */
final class OtlpResourceAttributes {
  private OtlpResourceAttributes() {}

  /** Prefix applied to {@code datadog.runtime_id} and process-tag resource attributes. */
  private static final String DATADOG_PREFIX = "datadog.";

  /** Marks that the Agent should not recompute trace metrics from the exported spans. */
  private static final String STATS_COMPUTED_KEY = "_dd.stats_computed";

  private static final Set<String> IGNORED_GLOBAL_TAGS =
      new HashSet<>(
          asList(
              "service",
              "env",
              "version",
              "service.name",
              "deployment.environment.name",
              "service.version",
              "telemetry.sdk.name",
              "telemetry.sdk.version",
              "telemetry.sdk.language"));

  /** Visits each resource attribute key/value pair with {@code visitor}. */
  static void visitResourceAttributes(
      Config config, Map<String, String> extraAttributes, BiConsumer<String, String> visitor) {
    String serviceName = config.getServiceName();
    String env = config.getEnv();
    String version = config.getVersion();

    visitor.accept("service.name", serviceName);
    if (!env.isEmpty()) {
      visitor.accept("deployment.environment.name", env);
    }
    if (!version.isEmpty()) {
      visitor.accept("service.version", version);
    }
    if (config.isReportHostName()) {
      String hostName = config.getHostName();
      if (hostName != null && !hostName.isEmpty()) {
        visitor.accept("host.name", hostName);
      }
    }
    visitor.accept("telemetry.sdk.name", "datadog");
    visitor.accept("telemetry.sdk.version", TRACER_VERSION);
    visitor.accept("telemetry.sdk.language", "java");

    config
        .getGlobalTags()
        .forEach(
            (key, value) -> {
              // ignore datadog tags and their otel equivalents that we map above
              if (!IGNORED_GLOBAL_TAGS.contains(key.toLowerCase(Locale.ROOT))) {
                visitor.accept(key, value);
              }
            });

    extraAttributes.forEach(visitor);
  }

  /**
   * Builds the extra resource attributes for the OTLP trace export: the {@code _dd.stats_computed}
   * marker when the SDK is computing OTLP span metrics, so a downstream Agent does not recompute
   * them from the exported spans.
   */
  static Map<String, String> traceResourceAttributes(Config config) {
    Map<String, String> attributes = new LinkedHashMap<>();
    if (config.isOtelTracesSpanMetricsEnabled()) {
      attributes.put(STATS_COMPUTED_KEY, "true");
    }
    return attributes;
  }

  static Map<String, String> datadogResourceAttributes(Config config) {
    Map<String, String> attributes = new LinkedHashMap<>();
    String runtimeId = config.getRuntimeId();
    if (runtimeId != null && !runtimeId.isEmpty()) {
      attributes.put(DATADOG_PREFIX + "runtime_id", runtimeId);
    }
    // Process tags arrive as "key:value" pairs; emit each as datadog.<key> = value.
    List<String> processTags = ProcessTags.getTagsAsStringList();
    if (processTags != null) {
      for (String tag : processTags) {
        int colon = tag.indexOf(':');
        if (colon > 0) {
          attributes.put(DATADOG_PREFIX + tag.substring(0, colon), tag.substring(colon + 1));
        }
      }
    }
    return attributes;
  }
}
