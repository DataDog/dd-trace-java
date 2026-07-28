package datadog.trace.core.otlp.common;

import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
import static datadog.trace.core.otlp.common.OtlpCommonJson.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.datadogResourceAttributes;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.traceResourceAttributes;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.visitResourceAttributes;

import datadog.json.JsonWriter;
import datadog.trace.api.Config;
import java.util.Collections;
import java.util.Map;

/** Provides a canned JSON fragment for OpenTelemetry's "resource.proto" JSON encoding. */
public final class OtlpResourceJson {
  private OtlpResourceJson() {}

  /** Vendor-neutral resource (no {@code datadog.*}). Used by the OTLP metric export. */
  public static final String RESOURCE_FRAGMENT =
      buildResourceFragment(Config.get(), Collections.emptyMap());

  /**
   * Resource that additionally carries {@code datadog.runtime_id} and process tags (each prefixed
   * {@code datadog.}). Used by the default-mode SDK trace-metrics export; omitted in OTel-semantics
   * mode.
   */
  public static final String RESOURCE_FRAGMENT_WITH_DATADOG_ATTRS =
      buildResourceFragment(Config.get(), datadogResourceAttributes(Config.get()));

  /**
   * Resource used by the OTLP trace export. Identical to {@link #RESOURCE_FRAGMENT} but adds the
   * {@code _dd.stats_computed} marker when the SDK is computing OTLP span metrics, so a downstream
   * Agent does not recompute them from the exported spans.
   */
  public static final String TRACE_RESOURCE_FRAGMENT =
      buildResourceFragment(Config.get(), traceResourceAttributes(Config.get()));

  static String buildResourceFragment(Config config, Map<String, String> extraAttributes) {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject();
      writer.name("attributes").beginArray();

      visitResourceAttributes(
          config, extraAttributes, (key, value) -> writeResourceAttribute(writer, key, value));

      writer.endArray();
      writer.endObject();
      return writer.toString();
    }
  }

  private static void writeResourceAttribute(JsonWriter writer, String key, String value) {
    writeAttribute(writer, STRING_ATTRIBUTE, key, value);
  }
}
