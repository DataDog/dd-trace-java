package com.datadog.profiling.uploader;

import com.datadog.profiling.otel.proto.OtlpProtoFields;
import com.datadog.profiling.otel.proto.OtlpResourceAttributes;
import com.datadog.profiling.otel.proto.ProtobufEncoder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Encodes a minimal OTLP ProfilesData message with no sample conversion — just metadata and the raw
 * JFR recording embedded as the {@code original_payload} blob. This is orders of magnitude faster
 * than full JFR→OTLP conversion since it skips JFR parsing, event processing, and dictionary
 * building entirely. The JFR bytes are streamed from the file so peak memory stays flat regardless
 * of recording size.
 */
final class LightweightOtlpEncoder {

  // string_table layout: index 0 is the null/unset sentinel required by the OTLP spec,
  // indices 1 and 2 back the sample_type/period_type ValueType below
  private static final int TYPE_STRINDEX_SAMPLES = 1;
  private static final int UNIT_STRINDEX_COUNT = 2;

  private LightweightOtlpEncoder() {}

  static byte[] encode(
      Path jfrFile, Instant start, Instant end, Map<String, String> resourceAttributes)
      throws IOException {
    ProtobufEncoder encoder = new ProtobufEncoder(64 * 1024);
    long startTimeNanos = start.getEpochSecond() * 1_000_000_000L + start.getNano();
    long endTimeNanos = end.getEpochSecond() * 1_000_000_000L + end.getNano();
    long jfrSize = Files.size(jfrFile);

    try {
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
                          encodeProfileUnchecked(
                              profileEncoder, startTimeNanos, endTimeNanos, jfrFile, jfrSize));
                });
          });

      // minimal dictionary: string_table with the index-0 sentinel plus the type/unit labels
      // referenced by sample_type/period_type
      encoder.writeNestedMessage(
          OtlpProtoFields.ProfilesData.DICTIONARY,
          dictionaryEncoder -> {
            dictionaryEncoder.writeTag(
                OtlpProtoFields.ProfilesDictionary.STRING_TABLE,
                ProtobufEncoder.WIRETYPE_LENGTH_DELIMITED);
            dictionaryEncoder.writeString(""); // index 0 (null/unset sentinel)
            dictionaryEncoder.writeTag(
                OtlpProtoFields.ProfilesDictionary.STRING_TABLE,
                ProtobufEncoder.WIRETYPE_LENGTH_DELIMITED);
            dictionaryEncoder.writeString("samples");
            dictionaryEncoder.writeTag(
                OtlpProtoFields.ProfilesDictionary.STRING_TABLE,
                ProtobufEncoder.WIRETYPE_LENGTH_DELIMITED);
            dictionaryEncoder.writeString("count");
          });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }

    return encoder.toByteArray();
  }

  private static void encodeProfileUnchecked(
      ProtobufEncoder encoder, long startTimeNanos, long endTimeNanos, Path jfrFile, long jfrSize) {
    try {
      encodeProfile(encoder, startTimeNanos, endTimeNanos, jfrFile, jfrSize);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void encodeProfile(
      ProtobufEncoder encoder, long startTimeNanos, long endTimeNanos, Path jfrFile, long jfrSize)
      throws IOException {
    // Field 1: sample_type / Field 5: period_type (count-based samples)
    encoder.writeNestedMessage(
        OtlpProtoFields.Profile.SAMPLE_TYPE,
        typeEncoder -> encodeValueType(typeEncoder, TYPE_STRINDEX_SAMPLES, UNIT_STRINDEX_COUNT));
    encoder.writeNestedMessage(
        OtlpProtoFields.Profile.PERIOD_TYPE,
        typeEncoder -> encodeValueType(typeEncoder, TYPE_STRINDEX_SAMPLES, UNIT_STRINDEX_COUNT));

    encoder.writeFixed64Field(OtlpProtoFields.Profile.TIME_UNIX_NANO, startTimeNanos);
    // clamped so a reversed caller-supplied window cannot underflow to a huge unsigned varint
    encoder.writeVarintField(
        OtlpProtoFields.Profile.DURATION_NANO, Math.max(0, endTimeNanos - startTimeNanos));
    encoder.writeVarintField(OtlpProtoFields.Profile.PERIOD, 1);
    encoder.writeBytesField(OtlpProtoFields.Profile.PROFILE_ID, generateProfileId());
    encoder.writeStringField(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD_FORMAT, "jfr");

    // Field 10: original_payload = raw JFR bytes, streamed from disk to keep peak heap flat
    try (InputStream jfrStream = Files.newInputStream(jfrFile)) {
      encoder.writeBytesField(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD, jfrStream, jfrSize);
    }
  }

  private static void encodeValueType(ProtobufEncoder encoder, int typeIndex, int unitIndex) {
    encoder.writeVarintField(OtlpProtoFields.ValueType.TYPE_STRINDEX, typeIndex);
    encoder.writeVarintField(OtlpProtoFields.ValueType.UNIT_STRINDEX, unitIndex);
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
