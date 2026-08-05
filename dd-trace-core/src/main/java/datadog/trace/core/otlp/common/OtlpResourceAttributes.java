package datadog.trace.core.otlp.common;

import static datadog.communication.ddagent.TracerVersion.TRACER_VERSION;
import static java.util.Arrays.asList;

import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import java.util.Collections;
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

  static void visitResourceAttributes(
      Config config,
      ExtraAttributes extraAttributes,
      BiConsumer<String, String> stringVisitor,
      BiConsumer<String, List<String>> stringArrayVisitor) {
    String serviceName = config.getServiceName();
    String env = config.getEnv();
    String version = config.getVersion();

    stringVisitor.accept("service.name", serviceName);
    if (!env.isEmpty()) {
      stringVisitor.accept("deployment.environment.name", env);
    }
    if (!version.isEmpty()) {
      stringVisitor.accept("service.version", version);
    }
    if (config.isReportHostName()) {
      String hostName = config.getHostName();
      if (hostName != null && !hostName.isEmpty()) {
        stringVisitor.accept("host.name", hostName);
      }
    }
    stringVisitor.accept("telemetry.sdk.name", "datadog");
    stringVisitor.accept("telemetry.sdk.version", TRACER_VERSION);
    stringVisitor.accept("telemetry.sdk.language", "java");

    config
        .getGlobalTags()
        .forEach(
            (key, value) -> {
              // ignore datadog tags and their otel equivalents that we map above
              if (!IGNORED_GLOBAL_TAGS.contains(key.toLowerCase(Locale.ROOT))) {
                stringVisitor.accept(key, value);
              }
            });

    extraAttributes.stringAttributes.forEach(stringVisitor);
    if (!extraAttributes.processTags.isEmpty()) {
      stringArrayVisitor.accept(PROCESS_TAGS_KEY, extraAttributes.processTags);
    }
  }

  private static final String PROCESS_TAGS_KEY = DATADOG_PREFIX + "process_tags";

  /**
   * Builds the extra resource attributes for the OTLP trace export: the {@code _dd.stats_computed}
   * marker when the SDK is computing OTLP span metrics, so a downstream Agent does not recompute
   * them from the exported spans.
   */
  static ExtraAttributes traceResourceAttributes(Config config) {
    Map<String, String> attributes = new LinkedHashMap<>();
    if (config.isOtelTracesSpanMetricsEnabled()) {
      attributes.put(STATS_COMPUTED_KEY, "true");
    }
    return new ExtraAttributes(attributes, Collections.emptyList());
  }

  static ExtraAttributes datadogResourceAttributes(Config config) {
    Map<String, String> attributes = new LinkedHashMap<>();
    String runtimeId = config.getRuntimeId();
    if (runtimeId != null && !runtimeId.isEmpty()) {
      attributes.put(DATADOG_PREFIX + "runtime_id", runtimeId);
    }
    // Mirrors SerializingMetricWriter's v0.6 ProcessTags shape; keep both in sync if that changes.
    List<String> processTags = ProcessTags.getTagsAsStringList();
    if (processTags != null && !processTags.isEmpty()) {
      return new ExtraAttributes(attributes, processTags);
    }
    return new ExtraAttributes(attributes, Collections.emptyList());
  }

  static final class ExtraAttributes {
    static final ExtraAttributes EMPTY =
        new ExtraAttributes(Collections.emptyMap(), Collections.emptyList());

    private final Map<String, String> stringAttributes;
    private final List<String> processTags;

    private ExtraAttributes(Map<String, String> stringAttributes, List<String> processTags) {
      this.stringAttributes = stringAttributes;
      this.processTags = processTags;
    }
  }
}
