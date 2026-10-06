package com.datadog.profiling.uploader;

import com.datadog.profiling.otel.proto.OtlpProtoFields;
import com.datadog.profiling.otel.proto.OtlpResourceAttributes;
import com.datadog.profiling.otel.proto.ProtobufEncoder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Encodes a minimal OTLP ProfilesData message with no sample conversion — just metadata and the raw
 * JFR recording embedded as the {@code original_payload} blob. This is orders of magnitude faster
 * than full JFR→OTLP conversion since it skips JFR parsing, event processing, and dictionary
 * building entirely.
 *
 * <p>All nested message lengths are computed up front from the recording size, so the message is
 * written in one pass into a single buffer and the JFR bytes are read from the file directly into
 * it. The buffer is reused across calls and only grows; it holds the whole message, so its size
 * tracks the largest recording encoded so far. Not thread-safe: the buffer returned by {@link
 * #encode} is only valid until the next call.
 */
final class LightweightOtlpEncoder {

  // string_table layout: index 0 is the null/unset sentinel required by the OTLP spec,
  // indices 1 and 2 back the sample_type/period_type ValueType below
  private static final int TYPE_STRINDEX_SAMPLES = 1;
  private static final int UNIT_STRINDEX_COUNT = 2;
  // buffer growth granularity — bounds the over-allocation while avoiding a reallocation for
  // every slightly larger recording
  private static final int BUFFER_GROWTH_ALIGNMENT = 1024 * 1024;

  private static final byte[] DICTIONARY_FIELD = encodeDictionaryField();

  // the ResourceProfiles.resource field; resource attributes are fixed for the uploader lifetime
  private final byte[] resourceField;
  // the Profile fields preceding original_payload
  private final ProtobufEncoder profileHeader = new ProtobufEncoder(128);
  private final byte[] profileId = new byte[16];
  private byte[] buffer = new byte[0];

  LightweightOtlpEncoder(Map<String, String> resourceAttributes) {
    ProtobufEncoder encoder = new ProtobufEncoder(512);
    OtlpResourceAttributes.writeResource(encoder, resourceAttributes);
    this.resourceField = encoder.toByteArray();
  }

  ByteBuffer encode(Path jfrFile, Instant start, Instant end) throws IOException {
    long startTimeNanos = start.getEpochSecond() * 1_000_000_000L + start.getNano();
    long endTimeNanos = end.getEpochSecond() * 1_000_000_000L + end.getNano();
    profileHeader.reset();
    encodeProfileHeader(startTimeNanos, endTimeNanos);
    int headerSize = profileHeader.size();

    try (FileChannel channel = FileChannel.open(jfrFile, StandardOpenOption.READ)) {
      long jfrSize = channel.size();
      long profileSize =
          headerSize + fieldPrefixSize(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD, jfrSize) + jfrSize;
      long scopeProfilesSize =
          fieldPrefixSize(OtlpProtoFields.ScopeProfiles.PROFILES, profileSize) + profileSize;
      long resourceProfilesSize =
          resourceField.length
              + fieldPrefixSize(OtlpProtoFields.ResourceProfiles.SCOPE_PROFILES, scopeProfilesSize)
              + scopeProfilesSize;
      long totalSize =
          fieldPrefixSize(OtlpProtoFields.ProfilesData.RESOURCE_PROFILES, resourceProfilesSize)
              + resourceProfilesSize
              + DICTIONARY_FIELD.length;
      ensureCapacity(totalSize);

      int pos =
          writeFieldPrefix(
              buffer, 0, OtlpProtoFields.ProfilesData.RESOURCE_PROFILES, resourceProfilesSize);
      System.arraycopy(resourceField, 0, buffer, pos, resourceField.length);
      pos += resourceField.length;
      pos =
          writeFieldPrefix(
              buffer, pos, OtlpProtoFields.ResourceProfiles.SCOPE_PROFILES, scopeProfilesSize);
      pos = writeFieldPrefix(buffer, pos, OtlpProtoFields.ScopeProfiles.PROFILES, profileSize);
      profileHeader.toByteBuffer().get(buffer, pos, headerSize);
      pos += headerSize;
      pos = writeFieldPrefix(buffer, pos, OtlpProtoFields.Profile.ORIGINAL_PAYLOAD, jfrSize);

      // the length prefixes above are derived from jfrSize, so exactly that many bytes are read;
      // a recording truncated concurrently fails the export instead of emitting a malformed message
      ByteBuffer target = ByteBuffer.wrap(buffer, pos, (int) jfrSize);
      while (target.hasRemaining()) {
        if (channel.read(target) < 0) {
          throw new IOException("JFR recording shrank while being encoded: " + jfrFile);
        }
      }
      pos += (int) jfrSize;

      System.arraycopy(DICTIONARY_FIELD, 0, buffer, pos, DICTIONARY_FIELD.length);
      pos += DICTIONARY_FIELD.length;
      return ByteBuffer.wrap(buffer, 0, pos);
    }
  }

  private void encodeProfileHeader(long startTimeNanos, long endTimeNanos) {
    // Field 1: sample_type / Field 5: period_type (count-based samples)
    profileHeader.writeNestedMessage(
        OtlpProtoFields.Profile.SAMPLE_TYPE,
        typeEncoder -> encodeValueType(typeEncoder, TYPE_STRINDEX_SAMPLES, UNIT_STRINDEX_COUNT));
    profileHeader.writeNestedMessage(
        OtlpProtoFields.Profile.PERIOD_TYPE,
        typeEncoder -> encodeValueType(typeEncoder, TYPE_STRINDEX_SAMPLES, UNIT_STRINDEX_COUNT));

    profileHeader.writeFixed64Field(OtlpProtoFields.Profile.TIME_UNIX_NANO, startTimeNanos);
    // clamped so a reversed window cannot underflow
    profileHeader.writeVarintField(
        OtlpProtoFields.Profile.DURATION_NANO, Math.max(0, endTimeNanos - startTimeNanos));
    profileHeader.writeVarintField(OtlpProtoFields.Profile.PERIOD, 1);
    fillProfileId();
    profileHeader.writeBytesField(OtlpProtoFields.Profile.PROFILE_ID, profileId);
    profileHeader.writeStringField(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD_FORMAT, "jfr");
  }

  private void ensureCapacity(long size) throws IOException {
    if (size > Integer.MAX_VALUE - BUFFER_GROWTH_ALIGNMENT) {
      throw new IOException("OTLP profile message too large: " + size + " bytes");
    }
    if (size > buffer.length) {
      long aligned =
          (size + BUFFER_GROWTH_ALIGNMENT - 1) / BUFFER_GROWTH_ALIGNMENT * BUFFER_GROWTH_ALIGNMENT;
      // every encode rewrites the whole message, so the old content is not copied over
      buffer = new byte[(int) aligned];
    }
  }

  private static long fieldPrefixSize(int fieldNumber, long length) {
    return varintSize((fieldNumber << 3) | ProtobufEncoder.WIRETYPE_LENGTH_DELIMITED)
        + varintSize(length);
  }

  private static int writeFieldPrefix(byte[] dest, int pos, int fieldNumber, long length) {
    pos = writeVarint(dest, pos, (fieldNumber << 3) | ProtobufEncoder.WIRETYPE_LENGTH_DELIMITED);
    return writeVarint(dest, pos, length);
  }

  private static int varintSize(long value) {
    int size = 1;
    while ((value & ~0x7FL) != 0) {
      value >>>= 7;
      size++;
    }
    return size;
  }

  private static int writeVarint(byte[] dest, int pos, long value) {
    while ((value & ~0x7FL) != 0) {
      dest[pos++] = (byte) ((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    dest[pos++] = (byte) value;
    return pos;
  }

  private static void encodeValueType(ProtobufEncoder encoder, int typeIndex, int unitIndex) {
    encoder.writeVarintField(OtlpProtoFields.ValueType.TYPE_STRINDEX, typeIndex);
    encoder.writeVarintField(OtlpProtoFields.ValueType.UNIT_STRINDEX, unitIndex);
  }

  // minimal dictionary: string_table with the index-0 sentinel plus the type/unit labels
  // referenced by sample_type/period_type
  private static byte[] encodeDictionaryField() {
    ProtobufEncoder encoder = new ProtobufEncoder(64);
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
    return encoder.toByteArray();
  }

  private void fillProfileId() {
    // ThreadLocalRandom over UUID.randomUUID (SecureRandom) — uniqueness suffices, and
    // SecureRandom can block on entropy
    long msb = ThreadLocalRandom.current().nextLong();
    long lsb = ThreadLocalRandom.current().nextLong();
    for (int i = 0; i < 8; i++) {
      profileId[i] = (byte) ((msb >> (56 - i * 8)) & 0xFF);
      profileId[i + 8] = (byte) ((lsb >> (56 - i * 8)) & 0xFF);
    }
  }
}
