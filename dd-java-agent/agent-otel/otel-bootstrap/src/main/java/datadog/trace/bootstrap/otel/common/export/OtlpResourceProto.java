package datadog.trace.bootstrap.otel.common.export;

import static datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor.STRING;
import static datadog.trace.bootstrap.otel.common.export.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.bootstrap.otel.common.export.OtlpCommonProto.recordMessage;
import static datadog.trace.bootstrap.otel.common.export.OtlpCommonProto.writeAttribute;
import static datadog.trace.bootstrap.otel.common.export.OtlpCommonProto.writeTag;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.StreamingBuffer;
import datadog.trace.api.Config;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Provides a canned message for OpenTelemetry's "resource.proto" wire protocol. */
public final class OtlpResourceProto {
  private OtlpResourceProto() {}

  public static final byte[] RESOURCE_MESSAGE = buildResourceMessage(Config.get());

  private static final Set<String> IGNORED_GLOBAL_TAGS =
      new HashSet<>(
          Arrays.asList(
              "service",
              "env",
              "version",
              "service.name",
              "deployment.environment.name",
              "service.version"));

  static byte[] buildResourceMessage(Config config) {
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

    config
        .getGlobalTags()
        .forEach(
            (key, value) -> {
              // ignore datadog tags and their otel equivalents that we map above
              if (!IGNORED_GLOBAL_TAGS.contains(key.toLowerCase(Locale.ROOT))) {
                writeResourceAttribute(buf, key, value);
              }
            });

    return recordMessage(buf, 1);
  }

  private static void writeResourceAttribute(StreamingBuffer buf, String key, String value) {
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeAttribute(buf, STRING, key, value);
  }
}
