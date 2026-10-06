package com.datadog.profiling.uploader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.otel.proto.OtlpProtoFields;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LightweightOtlpEncoderTest {

  private static final Instant START = Instant.ofEpochSecond(1000);
  private static final Instant END = Instant.ofEpochSecond(1060);

  @TempDir Path tempDir;

  private final LightweightOtlpEncoder encoder =
      new LightweightOtlpEncoder(Collections.singletonMap("service.name", "test-service"));

  @Test
  void embedsRecordingInWellFormedMessage() throws IOException {
    // 300 bytes needs a two-byte length varint at every nesting level
    byte[] jfr = randomBytes(300);

    assertPayload(encoder.encode(write("a.jfr", jfr), START, END), jfr);
  }

  @Test
  void reusesBufferForSmallerRecording() throws IOException {
    byte[] large = randomBytes(200_000);
    byte[] small = randomBytes(1_000);

    ByteBuffer first = encoder.encode(write("large.jfr", large), START, END);
    assertPayload(first, large);
    ByteBuffer second = encoder.encode(write("small.jfr", small), START, END);
    assertPayload(second, small);

    assertSame(first.array(), second.array());
  }

  @Test
  void growsBufferForLargerRecording() throws IOException {
    byte[] small = randomBytes(1_000);
    byte[] large = randomBytes(2 * 1024 * 1024);

    ByteBuffer first = encoder.encode(write("small.jfr", small), START, END);
    ByteBuffer second = encoder.encode(write("large.jfr", large), START, END);

    assertNotSame(first.array(), second.array());
    assertPayload(second, large);
  }

  @Test
  void shrinksBufferAboveRetentionCap() throws IOException {
    byte[] large = randomBytes(9 * 1024 * 1024);
    byte[] small = randomBytes(1_000);

    ByteBuffer first = encoder.encode(write("large.jfr", large), START, END);
    int largeCapacity = first.array().length;
    ByteBuffer second = encoder.encode(write("small.jfr", small), START, END);

    assertTrue(second.array().length < largeCapacity);
    assertPayload(second, small);
  }

  @Test
  void writesZeroValueEntryInEveryDictionaryTable() throws IOException {
    ByteBuffer encoded = encoder.encode(write("a.jfr", randomBytes(100)), START, END);
    byte[] message = new byte[encoded.remaining()];
    encoded.duplicate().get(message);

    Map<Integer, byte[]> dictionary =
        parse(parse(message).get(OtlpProtoFields.ProfilesData.DICTIONARY));
    int[] tables = {
      OtlpProtoFields.ProfilesDictionary.MAPPING_TABLE,
      OtlpProtoFields.ProfilesDictionary.LOCATION_TABLE,
      OtlpProtoFields.ProfilesDictionary.FUNCTION_TABLE,
      OtlpProtoFields.ProfilesDictionary.LINK_TABLE,
      OtlpProtoFields.ProfilesDictionary.ATTRIBUTE_TABLE,
      OtlpProtoFields.ProfilesDictionary.STACK_TABLE
    };
    for (int table : tables) {
      assertArrayEquals(new byte[0], dictionary.get(table), "table field " + table);
    }
  }

  private static void assertPayload(ByteBuffer encoded, byte[] expectedJfr) {
    byte[] message = new byte[encoded.remaining()];
    encoded.duplicate().get(message);

    Map<Integer, byte[]> profilesData = parse(message);
    assertNotNull(profilesData.get(OtlpProtoFields.ProfilesData.DICTIONARY));
    Map<Integer, byte[]> resourceProfiles =
        parse(profilesData.get(OtlpProtoFields.ProfilesData.RESOURCE_PROFILES));
    String resource =
        new String(
            resourceProfiles.get(OtlpProtoFields.ResourceProfiles.RESOURCE),
            StandardCharsets.ISO_8859_1);
    assertTrue(resource.contains("test-service"));
    Map<Integer, byte[]> scopeProfiles =
        parse(resourceProfiles.get(OtlpProtoFields.ResourceProfiles.SCOPE_PROFILES));
    Map<Integer, byte[]> profile = parse(scopeProfiles.get(OtlpProtoFields.ScopeProfiles.PROFILES));

    assertEquals(
        "jfr",
        new String(
            profile.get(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD_FORMAT), StandardCharsets.UTF_8));
    assertEquals(16, profile.get(OtlpProtoFields.Profile.PROFILE_ID).length);
    assertArrayEquals(expectedJfr, profile.get(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD));
  }

  /**
   * Returns the length-delimited fields of a message by field number; fails if any length prefix
   * does not match the bytes that follow, which is what a broken precomputed length would cause.
   */
  private static Map<Integer, byte[]> parse(byte[] message) {
    Map<Integer, byte[]> fields = new HashMap<>();
    int[] pos = {0};
    while (pos[0] < message.length) {
      long tag = readVarint(message, pos);
      int fieldNumber = (int) (tag >>> 3);
      switch ((int) (tag & 7)) {
        case 0:
          readVarint(message, pos);
          break;
        case 1:
          pos[0] += 8;
          break;
        case 2:
          int length = (int) readVarint(message, pos);
          fields.put(fieldNumber, Arrays.copyOfRange(message, pos[0], pos[0] + length));
          pos[0] += length;
          break;
        case 5:
          pos[0] += 4;
          break;
        default:
          throw new AssertionError("unexpected wire type in tag " + tag);
      }
    }
    assertEquals(message.length, pos[0], "message length does not match its fields");
    return fields;
  }

  private static long readVarint(byte[] buf, int[] pos) {
    long value = 0;
    for (int shift = 0; ; shift += 7) {
      byte b = buf[pos[0]++];
      value |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return value;
      }
    }
  }

  private Path write(String name, byte[] content) throws IOException {
    return Files.write(tempDir.resolve(name), content);
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new Random(size).nextBytes(bytes);
    return bytes;
  }
}
