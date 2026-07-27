package datadog.trace.core.otlp.common;

import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeTag;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.datadogResourceAttributes;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.traceResourceAttributes;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.visitResourceAttributes;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.StreamingBuffer;
import datadog.trace.api.Config;
import java.util.Collections;
import java.util.Map;

/** Provides a canned message for OpenTelemetry's "resource.proto" wire protocol. */
public final class OtlpResourceProto {
  private OtlpResourceProto() {}

  /** Vendor-neutral resource (no {@code datadog.*}). Used by the OTLP metric export. */
  public static final byte[] RESOURCE_MESSAGE =
      buildResourceMessage(Config.get(), Collections.emptyMap());

  /**
   * Resource that additionally carries {@code datadog.runtime_id} and process tags (each prefixed
   * {@code datadog.}). Used by the default-mode SDK trace-metrics export; omitted in OTel-semantics
   * mode.
   */
  public static final byte[] RESOURCE_MESSAGE_WITH_DATADOG_ATTRS =
      buildResourceMessage(Config.get(), datadogResourceAttributes(Config.get()));

  /**
   * Resource used by the OTLP trace export. Identical to {@link #RESOURCE_MESSAGE} but adds the
   * {@code _dd.stats_computed} marker when the SDK is computing OTLP span metrics, so a downstream
   * Agent does not recompute them from the exported spans.
   */
  public static final byte[] TRACE_RESOURCE_MESSAGE =
      buildResourceMessage(Config.get(), traceResourceAttributes(Config.get()));

  static byte[] buildResourceMessage(Config config, Map<String, String> extraAttributes) {
    GrowableBuffer buf = new GrowableBuffer(512);

    visitResourceAttributes(
        config, extraAttributes, (key, value) -> writeResourceAttribute(buf, key, value));

    OtlpProtoBuffer protobuf = new OtlpProtoBuffer(buf.capacity());
    int numBytes = protobuf.recordMessage(buf, 1);
    byte[] resourceMessage = new byte[numBytes];
    protobuf.flip().get(resourceMessage);

    return resourceMessage;
  }

  private static void writeResourceAttribute(StreamingBuffer buf, String key, String value) {
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeAttribute(buf, STRING_ATTRIBUTE, key, value);
  }
}
