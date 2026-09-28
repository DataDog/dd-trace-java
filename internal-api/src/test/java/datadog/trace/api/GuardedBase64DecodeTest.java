package datadog.trace.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.api.Functions.GuardedBase64Decode;
import datadog.trace.api.Functions.GuardedBase64Decode.DefinitelyNotBase64Exception;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GuardedBase64DecodeTest {

  private static final byte[] VALID =
      Base64.getEncoder().encode("x-datadog-trace-id".getBytes(UTF_8));
  private static final byte[] INVALID = "not-valid-base64!@#".getBytes(UTF_8);

  @Test
  void decodeReturnsDecodedStringForValidInput() {
    GuardedBase64Decode decode = new GuardedBase64Decode();
    assertEquals("x-datadog-trace-id", decode.decode(VALID));
  }

  @Test
  void decodeThrowsRealExceptionForInvalidInputWhenNotGuarded() {
    GuardedBase64Decode decode = new GuardedBase64Decode();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> decode.decode(INVALID));
    // the guard hasn't seen a failure yet, so this must be the JDK's own exception, not our
    // stack-trace-free stand-in
    assertEquals(IllegalArgumentException.class, e.getClass());
  }

  @Test
  void decodeThrowsFastFailStandInWhileGuarded() {
    GuardedBase64Decode decode = new GuardedBase64Decode();
    assertThrows(IllegalArgumentException.class, () -> decode.decode(INVALID));

    DefinitelyNotBase64Exception e =
        assertThrows(DefinitelyNotBase64Exception.class, () -> decode.decode(INVALID));
    assertEquals(0, e.getStackTrace().length);
  }

  @Test
  void decodeStillDecodesValidInputWhileGuarded() {
    GuardedBase64Decode decode = new GuardedBase64Decode();
    assertThrows(IllegalArgumentException.class, () -> decode.decode(INVALID));

    assertEquals("x-datadog-trace-id", decode.decode(VALID));
  }

  @Test
  void decodeClosesGuardAfterEnoughValidInput() {
    GuardedBase64Decode decode = new GuardedBase64Decode();
    assertThrows(IllegalArgumentException.class, () -> decode.decode(INVALID));

    for (int i = 0; i < 50; i++) {
      decode.decode(VALID);
    }

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> decode.decode(INVALID));
    // the guard has closed again, so this should be a real decode failure, not the stand-in
    assertEquals(IllegalArgumentException.class, e.getClass());
  }

  @Test
  void decodeOrNullReturnsDecodedStringForValidInput() {
    GuardedBase64Decode decode = new GuardedBase64Decode();
    assertEquals("x-datadog-trace-id", decode.decodeOrNull(VALID));
  }

  @Test
  void decodeOrNullReturnsNullForInvalidInput() {
    GuardedBase64Decode decode = new GuardedBase64Decode();
    assertNull(decode.decodeOrNull(INVALID));
  }

  @Test
  void decodeOrNullReturnsNullWhileGuarded() {
    GuardedBase64Decode decode = new GuardedBase64Decode();
    assertNull(decode.decodeOrNull(INVALID));
    assertNull(decode.decodeOrNull(INVALID));
  }
}
