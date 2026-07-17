package datadog.trace.core.otlp.common;

import static datadog.communication.ddagent.TracerVersion.TRACER_VERSION;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeTag;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.StreamingBuffer;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Provides a canned message for OpenTelemetry's "resource.proto" wire protocol. */
public final class OtlpResourceProto {
  private OtlpResourceProto() {}

  private static final Set<String> IGNORED_GLOBAL_TAGS =
      new HashSet<>(
          Arrays.asList(
              "service",
              "env",
              "version",
              "service.name",
              "deployment.environment.name",
              "service.version",
              "telemetry.sdk.name",
              "telemetry.sdk.version",
              "telemetry.sdk.language"));

  /** Prefix applied to {@code datadog.runtime_id} and process-tag resource attributes. */
  private static final String DATADOG_PREFIX = "datadog.";

  /** Vendor-neutral resource (no {@code datadog.*}). Used by the OTLP trace/metric export. */
  public static final byte[] RESOURCE_MESSAGE = buildResourceMessage(Config.get());

  /**
   * Resource that additionally carries {@code datadog.runtime_id} and process tags (each prefixed
   * {@code datadog.}). Used by the default-mode SDK trace-metrics export; omitted in OTel-semantics
   * mode.
   */
  public static final byte[] RESOURCE_MESSAGE_WITH_DATADOG_ATTRS =
      buildResourceMessage(Config.get(), true);

  static byte[] buildResourceMessage(Config config) {
    return buildResourceMessage(config, false);
  }

  static byte[] buildResourceMessage(Config config, boolean includeDatadogResourceAttributes) {
    GrowableBuffer buf = new GrowableBuffer(512);

    String serviceName = config.getServiceName();
    String env = config.getEnv();
    String version = config.getVersion();

    writeResourceAttribute(buf, "service.name", serviceName);
    if (!env.isEmpty()) {
      writeResourceAttribute(buf, "deployment.environment.name", env);
    }
    if (!version.isEmpty()) {
      writeResourceAttribute(buf, "service.version", version);
    }
    if (config.isReportHostName()) {
      String hostName = config.getHostName();
      if (hostName != null && !hostName.isEmpty()) {
        writeResourceAttribute(buf, "host.name", hostName);
      }
    }
    writeResourceAttribute(buf, "telemetry.sdk.name", "datadog");
    writeResourceAttribute(buf, "telemetry.sdk.version", TRACER_VERSION);
    writeResourceAttribute(buf, "telemetry.sdk.language", "java");

    config
        .getGlobalTags()
        .forEach(
            (key, value) -> {
              // ignore datadog tags and their otel equivalents that we map above
              if (!IGNORED_GLOBAL_TAGS.contains(key.toLowerCase(Locale.ROOT))) {
                writeResourceAttribute(buf, key, value);
              }
            });

    if (includeDatadogResourceAttributes) {
      writeDatadogResourceAttributes(buf, config);
    }

    OtlpProtoBuffer protobuf = new OtlpProtoBuffer(buf.capacity());
    int numBytes = protobuf.recordMessage(buf, 1);
    byte[] resourceMessage = new byte[numBytes];
    protobuf.flip().get(resourceMessage);

    return resourceMessage;
  }

  private static void writeDatadogResourceAttributes(StreamingBuffer buf, Config config) {
    String runtimeId = config.getRuntimeId();
    if (runtimeId != null && !runtimeId.isEmpty()) {
      writeResourceAttribute(buf, DATADOG_PREFIX + "runtime_id", runtimeId);
    }
    // Process tags arrive as "key:value" pairs; emit each as datadog.<key> = value.
    List<String> processTags = ProcessTags.getTagsAsStringList();
    if (processTags != null) {
      for (String tag : processTags) {
        int colon = tag.indexOf(':');
        if (colon > 0) {
          writeResourceAttribute(
              buf, DATADOG_PREFIX + tag.substring(0, colon), tag.substring(colon + 1));
        }
      }
    }
  }

  private static void writeResourceAttribute(StreamingBuffer buf, String key, String value) {
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeAttribute(buf, STRING_ATTRIBUTE, key, value);
  }
}
