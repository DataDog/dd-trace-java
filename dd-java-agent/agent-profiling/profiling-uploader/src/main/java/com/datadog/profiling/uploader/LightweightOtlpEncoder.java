package com.datadog.profiling.uploader;

import com.datadog.profiling.otel.proto.OtlpProtoFields;
import com.datadog.profiling.otel.proto.OtlpResourceAttributes;
import com.datadog.profiling.otel.proto.ProtobufEncoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Encodes a minimal OTLP ProfilesData message with no sample conversion — just metadata and the raw
 * JFR recording embedded as the {@code original_payload} blob. This is orders of magnitude faster
 * than full JFR→OTLP conversion since it skips JFR parsing, event processing, and dictionary
 * building entirely.
 */
final class LightweightOtlpEncoder {

  private LightweightOtlpEncoder() {}

  static byte[] encode(
      Path jfrFile, Instant start, Instant end, Map<String, String> resourceAttributes)
      throws IOException {
    byte[] jfrBytes = Files.readAllBytes(jfrFile);
    return encode(jfrBytes, start, end, resourceAttributes);
  }

  static byte[] encode(
      byte[] jfrBytes, Instant start, Instant end, Map<String, String> resourceAttributes) {
    ProtobufEncoder encoder = new ProtobufEncoder(64 * 1024 + jfrBytes.length);
    encoder.reset();

    long startTimeNanos = start.getEpochSecond() * 1_000_000_000L + start.getNano();
    long endTimeNanos = end.getEpochSecond() * 1_000_000_000L + end.getNano();

    encoder.writeNestedMessage(
        OtlpProtoFields.ProfilesData.RESOURCE_PROFILES,
        resourceEncoder -> {
          OtlpResourceAttributes.writeResource(resourceEncoder, resourceAttributes);
          resourceEncoder.writeNestedMessage(
              OtlpProtoFields.ResourceProfiles.SCOPE_PROFILES,
              scopeEncoder -> {
                scopeEncoder.writeNestedMessage(
                    OtlpProtoFields.ScopeProfiles.PROFILES,
                    profileEncoder ->
                        encodeProfile(profileEncoder, startTimeNanos, endTimeNanos, jfrBytes));
              });
        });

    // dictionary with just the null sentinels (index 0 entries)
    encoder.writeNestedMessage(OtlpProtoFields.ProfilesData.DICTIONARY, dictionaryEncoder -> {});

    return encoder.toByteArray();
  }

  private static void encodeProfile(
      ProtobufEncoder encoder, long startTimeNanos, long endTimeNanos, byte[] jfrBytes) {
    encoder.writeFixed64Field(OtlpProtoFields.Profile.TIME_UNIX_NANO, startTimeNanos);
    encoder.writeVarintField(OtlpProtoFields.Profile.DURATION_NANO, endTimeNanos - startTimeNanos);
    encoder.writeVarintField(OtlpProtoFields.Profile.PERIOD, 1);
    encoder.writeBytesField(OtlpProtoFields.Profile.PROFILE_ID, generateProfileId());
    encoder.writeStringField(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD_FORMAT, "jfr");

    // Field 10: original_payload = raw JFR bytes
    encoder.writeBytesField(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD, jfrBytes);
  }

  private static byte[] generateProfileId() {
    long msb = ThreadLocalRandom.current().nextLong();
    long lsb = ThreadLocalRandom.current().nextLong();
    byte[] bytes = new byte[16];
    for (int i = 0; i < 8; i++) {
      bytes[i] = (byte) ((msb >> (56 - i * 8)) & 0xFF);
      bytes[i + 8] = (byte) ((lsb >> (56 - i * 8)) & 0xFF);
    }
    return bytes;
  }
}
