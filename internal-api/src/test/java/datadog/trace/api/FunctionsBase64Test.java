package datadog.trace.api;

import static datadog.trace.api.Functions.UTF8_BYTES_TO_STRING;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Base64;
import java.util.function.Function;
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

    Function<byte[], String> decoder = Functions.base64Decode(UTF_8);
    assertEquals(original, decoder.apply(encoded));
  }

  @Test
  void base64DecodeReturnsNullForInvalidBase64() {
    Function<byte[], String> decoder = Functions.base64Decode(UTF_8);
    assertNull(decoder.apply("not-valid-base64!@#".getBytes(UTF_8)));
  }

  @Test
  void base64DecodeReturnsNullForUrlSafeChars() {
    // URL-safe Base64 uses '-' and '_' which the standard decoder rejects
    Function<byte[], String> decoder = Functions.base64Decode(UTF_8);
    assertNull(decoder.apply("abc-def_ghi".getBytes(UTF_8)));
  }

  @Test
  void base64DecodeInstanceIsNotNull() {
    assertNotNull(Functions.base64Decode(UTF_8));
  }
}
