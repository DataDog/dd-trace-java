package datadog.trace.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.api.internal.util.LongStringUtils;
import org.junit.jupiter.api.Test;

class AsciiSequenceTest {

  /** {@code value}'s UTF-8 bytes inside a larger array, at offset 3. */
  private static byte[] embedded(String value) {
    byte[] inner = value.getBytes(UTF_8);
    byte[] outer = new byte[inner.length + 5];
    outer[0] = 'x';
    System.arraycopy(inner, 0, outer, 3, inner.length);
    outer[outer.length - 1] = 'y';
    return outer;
  }

  @Test
  void viewsAsciiBytesAsChars() {
    String value = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    byte[] bytes = embedded(value);
    AsciiSequence view = AsciiSequence.ofAscii(bytes, 3, value.length());
    assertNotNull(view);
    assertEquals(value.length(), view.length());
    for (int i = 0; i < value.length(); i++) {
      assertEquals(value.charAt(i), view.charAt(i));
    }
    assertEquals(value, view.toString());
    assertEquals("0af7651916cd43dd8448eb211c80319c", view.subSequence(3, 35).toString());
    assertEquals("", view.subSequence(5, 5).toString());
  }

  @Test
  void nonAsciiBytesAreNotViewed() {
    byte[] bytes = embedded("café");
    assertNull(AsciiSequence.ofAscii(bytes, 3, "café".getBytes(UTF_8).length));
    // the ASCII part alone is fine
    assertNotNull(AsciiSequence.ofAscii(bytes, 3, 3));
  }

  @Test
  void toStringCopies() {
    byte[] bytes = embedded("12345");
    AsciiSequence view = AsciiSequence.ofAscii(bytes, 3, 5);
    String copy = view.toString();
    bytes[3] = '9';
    assertEquals("12345", copy);
    assertEquals('9', view.charAt(0)); // a view sees its array
  }

  @Test
  void parsesInPlace() {
    byte[] bytes = embedded("8264894952805660751");
    AsciiSequence view = AsciiSequence.ofAscii(bytes, 3, 19);
    assertEquals(
        LongStringUtils.parseUnsignedLong("8264894952805660751"),
        LongStringUtils.parseUnsignedLong(view, 0, view.length()));
  }

  @Test
  void checksBounds() {
    byte[] bytes = embedded("abc");
    assertThrows(IndexOutOfBoundsException.class, () -> AsciiSequence.ofAscii(bytes, -1, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> AsciiSequence.ofAscii(bytes, 0, -1));
    assertThrows(
        IndexOutOfBoundsException.class, () -> AsciiSequence.ofAscii(bytes, 6, bytes.length));
    AsciiSequence view = AsciiSequence.ofAscii(bytes, 3, 3);
    assertThrows(IndexOutOfBoundsException.class, () -> view.charAt(3));
    assertThrows(IndexOutOfBoundsException.class, () -> view.charAt(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> view.subSequence(2, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> view.subSequence(0, 4));
    assertThrows(IndexOutOfBoundsException.class, () -> view.subSequence(-1, 2));
  }
}
