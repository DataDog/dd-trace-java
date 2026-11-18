package datadog.trace.core.datastreams;

import static datadog.trace.api.datastreams.DataStreamsContext.fromTags;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.time.ControllableTimeSource;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The allocation-free encode and decode must agree with the JDK-based ones they replace. */
class PathwayContextCodecTest {
  private static final Random RANDOM = new Random(42);
  private static final String BASE64_CHARS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  /** A header in the DSM wire format: hash (8 bytes LE), then two zigzag varint timestamps. */
  private static String randomHeader(Random random) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    long hash = random.nextLong();
    for (int i = 0; i < 8; i++) {
      out.write((int) (hash >>> (8 * i)));
    }
    long start = random.nextBoolean() ? 1_700_000_000_000L + random.nextInt() : random.nextLong();
    writeSignedVarLong(out, start);
    writeSignedVarLong(out, start + random.nextInt(1_000_000));
    return Base64.getEncoder().encodeToString(out.toByteArray());
  }

  /** DDSketch's VarEncodingHelper.encodeSignedVarLong: at most 8 groups of 7 bits, then a byte. */
  private static void writeSignedVarLong(ByteArrayOutputStream out, long value) {
    long v = (value >> 63) ^ (value << 1);
    int groups = (63 - Long.numberOfLeadingZeros(v)) / 7;
    for (int i = 0; i < groups && i < 8; i++) {
      out.write((int) (v | 0x80));
      v >>>= 7;
    }
    out.write((int) v);
  }

  private static DefaultPathwayContext jdkDecodeOrNull(ControllableTimeSource time, String value) {
    try {
      return DefaultPathwayContext.decode(time, null, value);
    } catch (Throwable e) {
      return null;
    }
  }

  @Test
  void theUnsampledHeaderIsRejectedAsTruncatedByBothDecoders() {
    ControllableTimeSource time = new ControllableTimeSource();
    String header = UnsampledPathwayContext.INSTANCE.encode();

    assertEquals(8, Base64.getDecoder().decode(header).length);
    assertThrows(EOFException.class, () -> DefaultPathwayContext.decode(time, null, header));
    assertNull(DefaultPathwayContext.decodeOrNull(time, null, null, header));
    assertEquals(header, new String(UnsampledPathwayContext.INSTANCE.encodeBytes(), ISO_8859_1));
  }

  @Test
  void theUnsampledHeaderBytesAreAFreshArrayEachTime() {
    byte[] handedToUserCode = UnsampledPathwayContext.INSTANCE.encodeBytes();
    Arrays.fill(handedToUserCode, (byte) 'x');

    assertEquals(
        UnsampledPathwayContext.ENCODED,
        new String(UnsampledPathwayContext.INSTANCE.encodeBytes(), ISO_8859_1));
  }

  @Test
  void encodeBytesMatchesEncodeAfterCheckpoints() throws IOException {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_700_000_000_000_000_000L);
    DefaultPathwayContext context = new DefaultPathwayContext(time, null);
    for (int i = 0; i < 200; i++) {
      time.advance(RANDOM.nextInt(50_000_000));
      context.setCheckpoint(
          fromTags(
              DataStreamsTags.create(
                  "kafka",
                  i % 2 == 0
                      ? DataStreamsTags.Direction.OUTBOUND
                      : DataStreamsTags.Direction.INBOUND,
                  "topic-" + (i % 7))),
          point -> {});
      assertArrayEquals(context.encode().getBytes(ISO_8859_1), context.encodeBytes());
    }
  }

  @Test
  void encodeBytesMatchesEncodeForDecodedContexts() throws IOException {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_700_000_000_000_000_000L);
    for (int i = 0; i < 2000; i++) {
      DefaultPathwayContext context =
          DefaultPathwayContext.decode(time, null, randomHeader(RANDOM));
      assertArrayEquals(context.encode().getBytes(ISO_8859_1), context.encodeBytes());
    }
  }

  @Test
  void base64EncodeMatchesTheJdk() {
    for (int length = 0; length < 40; length++) {
      byte[] src = new byte[length + 3];
      RANDOM.nextBytes(src);
      assertArrayEquals(
          Base64.getEncoder().encode(Arrays.copyOf(src, length)),
          DefaultPathwayContext.base64Encode(src, length));
    }
  }

  @Test
  void decodeOrNullMatchesDecodeOnValidHeaders() throws IOException {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_700_000_000_000_000_000L);
    for (int i = 0; i < 2000; i++) {
      String header = randomHeader(RANDOM);
      DefaultPathwayContext expected = DefaultPathwayContext.decode(time, null, header);
      DefaultPathwayContext actual = DefaultPathwayContext.decodeOrNull(time, null, null, header);
      assertNotNull(actual, header);
      assertEquals(expected.getHash(), actual.getHash());
      assertEquals(expected.encode(), actual.encode());
      assertEquals(expected.isStarted(), actual.isStarted());
    }
  }

  @Test
  void decodeOrNullAgreesWithTheJdkOnMalformedInput() throws IOException {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_700_000_000_000_000_000L);
    String alphabet = BASE64_CHARS + "==-_ .é€";
    for (int i = 0; i < 20000; i++) {
      String input;
      if (i % 2 == 0) {
        StringBuilder sb = new StringBuilder();
        int length = RANDOM.nextInt(40);
        for (int j = 0; j < length; j++) {
          sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        input = sb.toString();
      } else {
        // truncate or mutate one character of a valid header
        char[] chars = randomHeader(RANDOM).toCharArray();
        if (RANDOM.nextBoolean()) {
          input = new String(chars, 0, RANDOM.nextInt(chars.length));
        } else {
          chars[RANDOM.nextInt(chars.length)] = alphabet.charAt(RANDOM.nextInt(alphabet.length()));
          input = new String(chars);
        }
      }
      DefaultPathwayContext expected = jdkDecodeOrNull(time, input);
      DefaultPathwayContext actual = DefaultPathwayContext.decodeOrNull(time, null, null, input);
      if (expected == null) {
        assertNull(actual, input);
      } else {
        assertNotNull(actual, input);
        assertEquals(expected.getHash(), actual.getHash(), input);
        assertEquals(expected.encode(), actual.encode(), input);
      }
    }
  }
}
