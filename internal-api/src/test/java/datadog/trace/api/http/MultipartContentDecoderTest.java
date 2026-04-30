package datadog.trace.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

public class MultipartContentDecoderTest {

  @Test
  void decodeBytesUsesDeclaredUtf8Charset() {
    String text = "héllo wörld";
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    assertEquals(
        text,
        MultipartContentDecoder.decodeBytes(bytes, bytes.length, "text/plain; charset=UTF-8"));
  }

  @Test
  void decodeBytesUsesDeclaredIso88591Charset() {
    String text = "café";
    byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
    assertEquals(
        text,
        MultipartContentDecoder.decodeBytes(bytes, bytes.length, "text/plain; charset=ISO-8859-1"));
  }

  @Test
  void decodeBytesDefaultsToMachineDefaultWhenNoCharset() {
    String text = "hello world";
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    assertEquals(text, MultipartContentDecoder.decodeBytes(bytes, bytes.length, "text/plain"));
  }

  @Test
  void decodeBytesDefaultsToMachineDefaultWhenNullContentType() {
    String text = "hello world";
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    assertEquals(text, MultipartContentDecoder.decodeBytes(bytes, bytes.length, null));
  }

  @Test
  void decodeBytesRespectsLengthParameter() {
    byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
    assertEquals("hello", MultipartContentDecoder.decodeBytes(bytes, 5, null));
  }

  @Test
  void decodeBytesReturnsEmptyStringForZeroLength() {
    assertEquals("", MultipartContentDecoder.decodeBytes(new byte[16], 0, null));
  }

  @Test
  void decodeBytesReplacesMalformedBytesWithReplacementCharacterUsingDeclaredCharset() {
    // 0xE9 (ISO-8859-1 'é') is not valid UTF-8; REPLACE substitutes U+FFFD
    byte[] bytes = "café".getBytes(StandardCharsets.ISO_8859_1);
    assertEquals(
        "caf�",
        MultipartContentDecoder.decodeBytes(bytes, bytes.length, "text/plain; charset=UTF-8"));
  }

  @Test
  void decodeBytesHandlesTruncationAtMultibyteCharacterBoundary() {
    // "€" encodes as 3 bytes in UTF-8: E2 82 AC
    byte[] complete = "hello€".getBytes(StandardCharsets.UTF_8); // 8 bytes
    // Pass only 6 bytes: "hello" + first byte of "€" (incomplete sequence)
    String result = MultipartContentDecoder.decodeBytes(complete, 6, "text/plain; charset=UTF-8");
    // Incomplete sequence → U+FFFD with declared charset, not fallback to JVM default
    assertEquals("hello�", result);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void extractCharsetReturnsNullForNullOrEmptyContentType(String contentType) {
    assertNull(MultipartContentDecoder.extractCharset(contentType));
  }

  @ParameterizedTest
  @ValueSource(strings = {"text/plain", "image/jpeg", "application/octet-stream"})
  void extractCharsetReturnsNullForContentTypeWithoutCharset(String contentType) {
    assertNull(MultipartContentDecoder.extractCharset(contentType));
  }

  @Test
  void extractCharsetReturnsNullForInvalidCharsetName() {
    assertNull(MultipartContentDecoder.extractCharset("text/plain; charset=NOTACHARSET"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "text/plain; CHARSET=UTF-8",
        "text/plain; Charset=UTF-8",
        "text/plain; charset=utf-8"
      })
  void extractCharsetIsCaseInsensitive(String contentType) {
    assertEquals("UTF-8", MultipartContentDecoder.extractCharset(contentType).name());
  }

  @ParameterizedTest
  @CsvSource({"text/plain; charset=UTF-8, UTF-8", "text/xml; charset=ISO-8859-1, ISO-8859-1"})
  void extractCharsetFromStandardContentType(String contentType, String expectedCharset) {
    assertEquals(expectedCharset, MultipartContentDecoder.extractCharset(contentType).name());
  }

  @Test
  void extractCharsetIgnoresSubstringMatchInParameterName() {
    // "xcharset=UTF-16" must not match; the real "charset=UTF-8" that follows must be used
    assertEquals(
        "UTF-8",
        MultipartContentDecoder.extractCharset("text/plain; xcharset=UTF-16; charset=UTF-8")
            .name());
  }

  @Test
  void extractCharsetReturnsNullWhenOnlySubstringMatchExists() {
    assertNull(MultipartContentDecoder.extractCharset("text/plain; xcharset=UTF-8"));
  }

  @Test
  void extractCharsetHandlesAdditionalParameters() {
    assertEquals(
        "UTF-8",
        MultipartContentDecoder.extractCharset("text/plain; charset=UTF-8; boundary=something")
            .name());
  }

  @ParameterizedTest
  @CsvSource({
    "text/plain; charset=\"UTF-8\", UTF-8",
    "text/xml; charset=\"ISO-8859-1\", ISO-8859-1"
  })
  void extractCharsetHandlesQuotedCharsetValue(String contentType, String expectedCharset) {
    assertEquals(expectedCharset, MultipartContentDecoder.extractCharset(contentType).name());
  }

  @Test
  void decodeBytesUsesQuotedDeclaredCharset() {
    String text = "café";
    byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
    assertEquals(
        text,
        MultipartContentDecoder.decodeBytes(
            bytes, bytes.length, "text/plain; charset=\"ISO-8859-1\""));
  }
}
