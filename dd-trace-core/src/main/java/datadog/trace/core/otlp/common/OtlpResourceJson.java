package datadog.trace.core.otlp.common;

import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ARRAY_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
import static datadog.trace.core.otlp.common.OtlpCommonJson.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.datadogResourceAttributes;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.traceResourceAttributes;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.visitResourceAttributes;

import datadog.json.JsonWriter;
import datadog.trace.api.Config;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Provides a canned JSON fragment for OpenTelemetry's "resource.proto" JSON encoding. */
public final class OtlpResourceJson {
  private OtlpResourceJson() {}

  /** Vendor-neutral resource (no {@code datadog.*}). Used by the OTLP metric export. */
  public static final String RESOURCE_FRAGMENT =
      buildResourceFragment(Config.get(), Collections.emptyMap());

  /**
   * Resource that additionally carries {@code datadog.runtime_id} and process tags (each prefixed
   * {@code datadog.}). Used by the SDK trace-metrics export.
   */
  public static final String RESOURCE_FRAGMENT_WITH_DATADOG_ATTRS =
      buildResourceFragment(Config.get(), datadogResourceAttributes(Config.get()));

  private static final Map<String, Object> TRACE_RESOURCE_ATTRIBUTES =
      traceResourceAttributes(Config.get());

  public static String buildTraceResourceFragment(Map<String, ?> globalTags) {
    return buildResourceFragment(Config.get(), globalTags, TRACE_RESOURCE_ATTRIBUTES);
  }

  static String buildResourceFragment(Config config, Map<String, Object> extraAttributes) {
    return buildResourceFragment(config, config.getGlobalTags(), extraAttributes);
  }

  static String buildResourceFragment(
      Config config, Map<String, ?> globalTags, Map<String, Object> extraAttributes) {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject();
      writer.name("attributes").beginArray();

      visitResourceAttributes(
          config,
          globalTags,
          extraAttributes,
          (key, value) -> writeResourceAttribute(writer, key, value));

      writer.endArray();
      writer.endObject();
      return writer.toString();
    }
  }

  /**
   * {@code value} is a {@link String}, except {@code datadog.process_tags}: a {@code List<String>}.
   */
  private static void writeResourceAttribute(JsonWriter writer, String key, Object value) {
    if (value instanceof List) {
      writeAttribute(writer, STRING_ARRAY_ATTRIBUTE, key, value);
    } else {
      writeAttribute(writer, STRING_ATTRIBUTE, key, value);
    }
  }
}
