package datadog.trace.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.Charset;
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
  void decodeBytesUsesMachineDefaultFallbackWhenBytesCannotBeDecoded() {
    byte[] bytes = "café".getBytes(StandardCharsets.ISO_8859_1);
    String expected = new String(bytes, Charset.defaultCharset());
    assertEquals(expected, MultipartContentDecoder.decodeBytes(bytes, bytes.length, null));
  }

  @Test
  void decodeBytesUsesMachineDefaultFallbackWhenDeclaredCharsetCannotDecodeBytes() {
    byte[] bytes = "café".getBytes(StandardCharsets.ISO_8859_1);
    String expected = new String(bytes, Charset.defaultCharset());
    assertEquals(
        expected,
        MultipartContentDecoder.decodeBytes(bytes, bytes.length, "text/plain; charset=UTF-8"));
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
  void extractCharsetHandlesAdditionalParameters() {
    assertEquals(
        "UTF-8",
        MultipartContentDecoder.extractCharset("text/plain; charset=UTF-8; boundary=something")
            .name());
  }
}
