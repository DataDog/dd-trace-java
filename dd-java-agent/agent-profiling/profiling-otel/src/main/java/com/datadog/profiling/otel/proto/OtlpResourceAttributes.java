package com.datadog.profiling.otel.proto;

import java.util.Map;

/**
 * Writes the {@code resource} message of {@code ResourceProfiles} with its standard {@code
 * KeyValue} attributes (opentelemetry.proto.resource.v1.Resource field 1, using
 * opentelemetry.proto.common.v1.KeyValue with a plain string AnyValue — no string-table
 * references).
 */
public final class OtlpResourceAttributes {

  private OtlpResourceAttributes() {}

  /**
   * Writes the nested {@code resource} message (ResourceProfiles field 1).
   *
   * @param encoder the encoder positioned inside the {@code ResourceProfiles} message
   * @param attributes resource attribute key/value pairs; entries with a null or empty value are
   *     skipped
   */
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
                  // KeyValue: field 1 = key, field 2 = value (AnyValue)
                  attributeEncoder.writeStringField(OtlpProtoFields.KeyValue.KEY, key);
                  attributeEncoder.writeNestedMessage(
                      OtlpProtoFields.KeyValue.VALUE,
                      valueEncoder ->
                          // AnyValue: field 1 = string_value
                          valueEncoder.writeStringField(
                              OtlpProtoFields.AnyValue.STRING_VALUE, value));
                });
          }
        });
  }
}
