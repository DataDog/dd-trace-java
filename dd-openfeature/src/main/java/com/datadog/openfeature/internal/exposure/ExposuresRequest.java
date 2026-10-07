package com.datadog.openfeature.internal.exposure;

import static com.datadog.openfeature.internal.JsonWriting.writeKeyObject;
import static com.datadog.openfeature.internal.JsonWriting.writeValue;

import com.datadog.openfeature.internal.JsonWriting;
import java.util.List;
import java.util.Map;

public class ExposuresRequest {
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
    return JsonWriting.write(
        generator -> {
          generator.writeStartObject();
          generator.writeFieldName("context");
          writeValue(generator, this.context);
          generator.writeArrayFieldStart("exposures");
          for (final ExposureEvent exposure : this.exposures) {
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
            generator.writeEndObject();
          }
          generator.writeEndArray();
          generator.writeEndObject();
        });
  }
}
