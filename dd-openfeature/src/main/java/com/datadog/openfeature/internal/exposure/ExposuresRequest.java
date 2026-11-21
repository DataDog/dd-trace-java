package com.datadog.openfeature.internal.exposure;

import static com.datadog.openfeature.internal.JsonWriting.writeKeyObject;
import static com.datadog.openfeature.internal.JsonWriting.writeValue;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.datadog.openfeature.internal.JsonWriting;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ExposuresRequest {
  /** The end of a payload, after its last exposure. */
  public static final byte[] SUFFIX = "]}".getBytes(UTF_8);

  public final Map<String, String> context;
  public final List<ExposureEvent> exposures;

  public ExposuresRequest(final Map<String, String> context, final List<ExposureEvent> exposures) {
    this.context = context;
    this.exposures = exposures;
  }

  /**
   * @return the UTF-8 JSON payload.
   */
  public byte[] serialize() {
    return JsonWriting.write(generator -> {
      generator.writeStartObject();
      generator.writeFieldName("context");
      writeValue(generator, this.context);
      generator.writeArrayFieldStart("exposures");
      for (final ExposureEvent exposure : this.exposures) {
        writeExposure(generator, exposure);
      }
      generator.writeEndArray();
      generator.writeEndObject();
    });
  }

  /**
   * The start of a payload, up to its first exposure: a payload is this prefix, the exposures
   * separated by commas, then {@link #SUFFIX}.
   *
   * @param context the event context.
   * @return the UTF-8 prefix.
   */
  public static byte[] prefix(final Map<String, String> context) {
    final byte[] contextJson = JsonWriting.write(generator -> writeValue(generator, context));
    return ("{\"context\":" + new String(contextJson, UTF_8) + ",\"exposures\":[").getBytes(UTF_8);
  }

  /**
   * Writes one exposure of a payload.
   *
   * @param generator the generator to write to.
   * @param exposure the exposure.
   * @throws IOException if the exposure could not be written.
   */
  public static void writeExposure(final JsonGenerator generator, final ExposureEvent exposure)
      throws IOException {
    generator.writeStartObject();
    generator.writeNumberField("timestamp", exposure.timestamp);
    writeKeyObject(generator, "allocation", exposure.allocation.key);
    writeKeyObject(generator, "flag", exposure.flag.key);
    writeKeyObject(generator, "variant", exposure.variant.key);
    generator.writeObjectFieldStart("subject");
    if (exposure.subject.id != null) {
      generator.writeStringField("id", exposure.subject.id);
    }
    if (exposure.subject.attributes != null) {
      generator.writeFieldName("attributes");
      writeValue(generator, exposure.subject.attributes);
    }
    generator.writeEndObject();
    if (exposure.serial_id != null) {
      generator.writeNumberField("serial_id", exposure.serial_id);
    }
    if (exposure.features != null && !exposure.features.isEmpty()) {
      generator.writeFieldName("features");
      writeValue(generator, exposure.features);
    }
    generator.writeEndObject();
  }
}
