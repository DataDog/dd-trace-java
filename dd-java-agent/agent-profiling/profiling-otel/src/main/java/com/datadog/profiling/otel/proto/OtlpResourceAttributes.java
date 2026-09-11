package com.datadog.profiling.otel.proto;

import java.util.Map;

/**
 * Writes the {@code resource} message of {@code ResourceProfiles} with standard {@code KeyValue}
 * attributes; entries with a null or empty value are skipped.
 */
public final class OtlpResourceAttributes {

  private OtlpResourceAttributes() {}

  public static void writeResource(ProtobufEncoder encoder, Map<String, String> attributes) {
    encoder.writeNestedMessage(
        OtlpProtoFields.ResourceProfiles.RESOURCE,
        resourceEncoder -> {
          for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isEmpty()) {
              continue;
            }
            resourceEncoder.writeNestedMessage(
                OtlpProtoFields.Resource.ATTRIBUTES,
                attributeEncoder -> {
                  attributeEncoder.writeStringField(OtlpProtoFields.KeyValue.KEY, key);
                  attributeEncoder.writeNestedMessage(
                      OtlpProtoFields.KeyValue.VALUE,
                      valueEncoder ->
                          valueEncoder.writeStringField(
                              OtlpProtoFields.AnyValue.STRING_VALUE, value));
                });
          }
        });
  }
}
