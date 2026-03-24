package datadog.trace.bootstrap.otel.common.export;

import static datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor.STRING;
import static datadog.trace.bootstrap.otel.common.export.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.bootstrap.otel.common.export.OtlpCommonProto.recordMessage;
import static datadog.trace.bootstrap.otel.common.export.OtlpCommonProto.writeAttribute;
import static datadog.trace.bootstrap.otel.common.export.OtlpCommonProto.writeTag;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.StreamingBuffer;
import datadog.trace.api.Config;

/** Provides a canned message for OpenTelemetry's "resource.proto" wire protocol. */
public final class OtlpResourceProto {
  private OtlpResourceProto() {}

  private static final byte[] RESOURCE_MESSAGE = buildResourceMessage(Config.get());

  /** Writes the resource message in protobuf format to the given buffer. */
  public static void writeResourceMessage(StreamingBuffer buf) {
    buf.put(RESOURCE_MESSAGE);
  }

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
              // ignore datadog tags that we map above
              if (!"service".equalsIgnoreCase(key)
                  && !"env".equalsIgnoreCase(key)
                  && !"version".equalsIgnoreCase(key)) {
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
