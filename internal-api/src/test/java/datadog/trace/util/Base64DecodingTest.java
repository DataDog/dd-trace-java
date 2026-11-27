package datadog.trace.util;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Base64DecodingTest {

  @ParameterizedTest
  @ValueSource(
      strings = {"", "Zg==", "Zm8=", "Zm9v", "Zg", "Zm8", "aGVsbG8gd29ybGQ=", "+/+/", "AA=="})
  void decodesLikeJdk(String input) {
    byte[] expected = Base64.getDecoder().decode(input);

    assertArrayEquals(expected, Base64Decoding.decode(input));
    assertArrayEquals(expected, Base64Decoding.decode(input.getBytes(ISO_8859_1)));
    assertEquals(
        ByteBuffer.wrap(expected),
        Base64Decoding.decode(ByteBuffer.wrap(input.getBytes(ISO_8859_1))));
  }

  @Test
  void decodesEveryLengthLikeJdk() {
    byte[] data = new byte[300];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) (i * 31);
    }
    for (int len = 0; len <= data.length; len++) {
      byte[] slice = Arrays.copyOf(data, len);
      String padded = Base64.getEncoder().encodeToString(slice);
      String unpadded = Base64.getEncoder().withoutPadding().encodeToString(slice);

      assertArrayEquals(slice, Base64Decoding.decode(padded), "padded, length " + len);
      assertArrayEquals(slice, Base64Decoding.decode(unpadded), "unpadded, length " + len);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Z", // lone trailing character
        "Zm9vY",
        "=",
        "==",
        "Zg=",
        "Zm9vZg=",
        "Zm9vZm8==",
        "Z===",
        "====",
        "Zm9v!",
        "Zm 9v",
        "Zm9v\n",
        "Zm-v", // URL-safe alphabet
        "Zm_v",
        "Z=g=",
        "Zg==Zg==",
        "Zg==="
      })
  void returnsNullForInvalidInputThatJdkRejects(String input) {
    assertThrows(IllegalArgumentException.class, () -> Base64.getDecoder().decode(input));

    assertNull(Base64Decoding.decode(input));
    assertNull(Base64Decoding.decode(input.getBytes(ISO_8859_1)));
    ByteBuffer buffer = ByteBuffer.wrap(input.getBytes(ISO_8859_1));
    assertNull(Base64Decoding.decode(buffer));
    assertEquals(0, buffer.position());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"Z", "Zm9vY", "=", "==", "Zg=", "Zm9vZg=", "Zm9vZm8==", "Z===", "====", "Zg==="})
  void rejectsInvalidLengthOrPaddingBeforeDecoding(String input) {
    assertFalse(Base64Decoding.hasValidShape(input.getBytes(ISO_8859_1)));
  }

  @Test
  void acceptsShapeOfEveryValidEncoding() {
    for (int len = 0; len <= 300; len++) {
      byte[] data = new byte[len];
      assertTrue(Base64Decoding.hasValidShape(Base64.getEncoder().encode(data)), "padded " + len);
      assertTrue(
          Base64Decoding.hasValidShape(Base64.getEncoder().withoutPadding().encode(data)),
          "unpadded " + len);
    }
  }

  @Test
  void returnsNullForNonLatin1Characters() {
    assertNull(Base64Decoding.decode("Zm9v\u00e9g=="));
    assertNull(Base64Decoding.decode("Zm9v\u4e2d"));
  }

  @Test
  void returnsNullForEveryInvalidSingleByteSubstitution() {
    byte[] valid = "Zm9vYmFy".getBytes(ISO_8859_1);
    for (int b = 0; b < 256; b++) {
      byte[] input = valid.clone();
      input[3] = (byte) b;
      boolean jdkAccepts;
      try {
        Base64.getDecoder().decode(input);
        jdkAccepts = true;
      } catch (IllegalArgumentException e) {
        jdkAccepts = false;
      }
      byte[] decoded = Base64Decoding.decode(input);
      assertEquals(jdkAccepts, decoded != null, "byte " + b);
    }
  }

  @Test
  void decodesOnlyTheRemainingBufferRegion() {
    // the backing array is 7 bytes, but only the 4-byte region "Zm8=" is decoded
    ByteBuffer buffer = ByteBuffer.wrap("xxxZm8=".getBytes(ISO_8859_1));
    buffer.position(3);

    assertEquals(ByteBuffer.wrap("fo".getBytes(ISO_8859_1)), Base64Decoding.decode(buffer));
    assertEquals(7, buffer.position());
  }

  @Test
  void decodesReadOnlyBuffer() {
    ByteBuffer buffer = ByteBuffer.wrap("Zm9v".getBytes(ISO_8859_1)).asReadOnlyBuffer();

    assertEquals(ByteBuffer.wrap("foo".getBytes(ISO_8859_1)), Base64Decoding.decode(buffer));
  }
}
