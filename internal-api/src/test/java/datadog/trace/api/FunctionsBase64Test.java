package datadog.trace.api;

import static datadog.trace.api.Functions.BASE64_DECODE;
import static datadog.trace.api.Functions.UTF8_BYTES_TO_STRING;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class FunctionsBase64Test {

  @Test
  void utf8BytesToStringConvertsBytes() {
    byte[] bytes = "hello".getBytes(UTF_8);
    assertEquals("hello", UTF8_BYTES_TO_STRING.apply(bytes));
  }

  @Test
  void base64DecodeDecodesValidInput() {
    String original = "x-datadog-trace-id";
    byte[] encoded = Base64.getEncoder().encode(original.getBytes(UTF_8));
    assertEquals(original, BASE64_DECODE.apply(encoded));
  }

  @Test
  void base64DecodeReturnsNullForInvalidBase64() {
    assertNull(BASE64_DECODE.apply("not-valid-base64!@#".getBytes(UTF_8)));
  }

  @Test
  void base64DecodeReturnsNullForUrlSafeChars() {
    // URL-safe Base64 uses '-' and '_' which the standard decoder rejects
    assertNull(BASE64_DECODE.apply("abc-def_ghi".getBytes(UTF_8)));
  }

  @Test
  void base64DecodeIsNotNull() {
    assertNotNull(BASE64_DECODE);
  }
}
