package datadog.trace.bootstrap.instrumentation.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.ByteBuffer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class Utf8ByteStringTest {

  @TableTest({
    "scenario        | str          ",
    "null            |              ",
    "foo             | foo          ",
    "bar             | bar          ",
    "a longer string | alongerstring"
  })
  void wrapStringAndProduceTheRightBytesAndString(String str) {
    UTF8BytesString utf8String = UTF8BytesString.create(str);

    if (str == null) {
      assertNull(utf8String);
      return;
    }
    assertWrapped(str, str.getBytes(UTF_8), utf8String);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("bytesArguments")
  void wrapBytesAndProduceTheRightStringAndBytes(String str, byte[] bytes) {
    UTF8BytesString utf8String = UTF8BytesString.create(bytes);

    if (str == null) {
      assertNull(utf8String);
      return;
    }
    assertWrapped(str, bytes, utf8String);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("bytesArguments")
  void wrapByteBufferAndProduceTheRightStringAndBytes(String str, byte[] bytes) {
    UTF8BytesString utf8String =
        UTF8BytesString.create(bytes != null ? ByteBuffer.wrap(bytes) : (ByteBuffer) null);

    if (str == null) {
      assertNull(utf8String);
      return;
    }
    assertWrapped(str, bytes, utf8String);
  }

  static Stream<Arguments> bytesArguments() {
    return Stream.of(
        arguments(null, null),
        arguments("foo", new byte[] {0x66, 0x6f, 0x6f}),
        arguments("bar", new byte[] {0x62, 0x61, 0x72}),
        arguments(
            "alongerstring",
            new byte[] {
              0x61, 0x6c, 0x6f, 0x6e, 0x67, 0x65, 0x72, 0x73, 0x74, 0x72, 0x69, 0x6e, 0x67
            }));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("behaveLikeAProperCharSequenceArguments")
  void behaveLikeAProperCharSequence(String description, CharSequence chars) {
    UTF8BytesString utf8String = UTF8BytesString.create(chars);

    if (chars == null) {
      assertNull(utf8String);
      return;
    }
    assertEquals(chars.toString(), utf8String.toString());
    assertEquals(chars.length(), utf8String.length());
    for (int index = 0; index < chars.length(); index++) {
      assertEquals(chars.charAt(index), utf8String.charAt(index));
    }
    assertEquals(
        chars.subSequence(1, chars.length()).toString(),
        utf8String.subSequence(1, chars.length()).toString());
  }

  static Stream<Arguments> behaveLikeAProperCharSequenceArguments() {
    return Stream.of(
        arguments("null", null),
        arguments("String", "foo"),
        arguments("StringBuffer", new StringBuffer("bar")),
        arguments("long StringBuffer", new StringBuffer("someotherlongstring")),
        arguments("UTF8BytesString", UTF8BytesString.create("utf8string")));
  }

  private static void assertWrapped(String str, byte[] bytes, UTF8BytesString utf8String) {
    assertEquals(str, utf8String.toString());
    assertEquals(str.hashCode(), utf8String.hashCode());
    ByteBuffer utf8Bytes = ByteBuffer.allocate(bytes.length);
    utf8String.transferTo(utf8Bytes);
    // check that we get back the same byte array
    assertArrayEquals(bytes, utf8Bytes.array());
    // argument order matters: exercises UTF8BytesString.equals
    assertNotEquals(utf8String, null);
    assertNotEquals(utf8String, str);
    assertNotEquals(utf8String, UTF8BytesString.create("somethingcompletelydifferent"));
  }
}
