package com.datadog.profiling.uploader;

import com.datadog.profiling.otel.proto.OtlpProtoFields;
import com.datadog.profiling.otel.proto.ProtobufEncoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Encodes a minimal OTLP ProfilesData message with no sample conversion — just metadata and the raw
 * JFR recording embedded as the {@code original_payload} blob. This is orders of magnitude faster
 * than full JFR→OTLP conversion since it skips JFR parsing, event processing, and dictionary
 * building entirely.
 */
final class LightweightOtlpEncoder {

  private LightweightOtlpEncoder() {}

  /**
   * Encodes a JFR recording as a lightweight OTLP ProfilesData protobuf message.
   *
   * @param jfrFile path to the JFR recording
   * @param start recording start time
   * @param end recording end time
   * @return OTLP protobuf bytes
   * @throws IOException if reading the JFR file fails
   */
  static byte[] encode(Path jfrFile, Instant start, Instant end) throws IOException {
    byte[] jfrBytes = Files.readAllBytes(jfrFile);
    return encode(jfrBytes, start, end);
  }

  /**
   * Encodes raw JFR bytes as a lightweight OTLP ProfilesData protobuf message.
   *
   * @param jfrBytes raw JFR recording bytes
   * @param start recording start time
   * @param end recording end time
   * @return OTLP protobuf bytes
   */
  static byte[] encode(byte[] jfrBytes, Instant start, Instant end) {
    ProtobufEncoder encoder = new ProtobufEncoder(64 * 1024 + jfrBytes.length);
    encoder.reset();

    long startTimeNanos = start.getEpochSecond() * 1_000_000_000L + start.getNano();
    long endTimeNanos = end.getEpochSecond() * 1_000_000_000L + end.getNano();

    // ProfilesData message
    // Field 1: resource_profiles (repeated)
    encoder.writeNestedMessage(
        OtlpProtoFields.ProfilesData.RESOURCE_PROFILES,
        resourceEncoder -> {
          // ResourceProfiles
          // Field 2: scope_profiles (repeated)
          resourceEncoder.writeNestedMessage(
              OtlpProtoFields.ResourceProfiles.SCOPE_PROFILES,
              scopeEncoder -> {
                // ScopeProfiles
                // Field 2: profiles (repeated)
                scopeEncoder.writeNestedMessage(
                    OtlpProtoFields.ScopeProfiles.PROFILES,
                    profileEncoder ->
                        encodeProfile(profileEncoder, startTimeNanos, endTimeNanos, jfrBytes));
              });
        });

    // Field 2: dictionary — minimal, just the null sentinels (index 0 entries)
    encoder.writeNestedMessage(OtlpProtoFields.ProfilesData.DICTIONARY, dictionaryEncoder -> {});

    return encoder.toByteArray();
  }

  private static void encodeProfile(
      ProtobufEncoder encoder, long startTimeNanos, long endTimeNanos, byte[] jfrBytes) {
    // Profile message

    // Field 3: time_unix_nano
    encoder.writeFixed64Field(OtlpProtoFields.Profile.TIME_UNIX_NANO, startTimeNanos);

    // Field 4: duration_nano
    encoder.writeVarintField(OtlpProtoFields.Profile.DURATION_NANO, endTimeNanos - startTimeNanos);

    // Field 6: period (1 for count-based)
    encoder.writeVarintField(OtlpProtoFields.Profile.PERIOD, 1);

    // Field 7: profile_id (16 bytes UUID)
    encoder.writeBytesField(OtlpProtoFields.Profile.PROFILE_ID, generateProfileId());

    // Field 9: original_payload_format = "jfr"
    encoder.writeStringField(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD_FORMAT, "jfr");

    // Field 10: original_payload = raw JFR bytes
    encoder.writeBytesField(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD, jfrBytes);
  }

  private static byte[] generateProfileId() {
    long msb = UUID.randomUUID().getMostSignificantBits();
    long lsb = UUID.randomUUID().getLeastSignificantBits();
    byte[] bytes = new byte[16];
    for (int i = 0; i < 8; i++) {
      bytes[i] = (byte) ((msb >> (56 - i * 8)) & 0xFF);
      bytes[i + 8] = (byte) ((lsb >> (56 - i * 8)) & 0xFF);
    }
    return bytes;
  }
}
