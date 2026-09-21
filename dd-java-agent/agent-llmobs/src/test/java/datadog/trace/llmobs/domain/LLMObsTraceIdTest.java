package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class LLMObsTraceIdTest {

  @TableTest({
    "scenario      | hex                                | expectedWire                             ",
    "zero          | '00000000000000000000000000000000' | '0'                                      ",
    "one           | '00000000000000000000000000000001' | '1'                                      ",
    "64-bit value  | '0000000000000000ffffffffffffffff' | '18446744073709551615'                   ",
    "full 128 bits | 'ffffffffffffffffffffffffffffffff' | '340282366920938463463374607431768211455'",
    "mixed         | '6d0b1e9c00000000a1b2c3d4e5f60718' | '144943587637881175037429778545693296408'"
  })
  void convertsStoredHexToTheDecimalTheWireCarries(String hex, String expectedWire) {
    assertEquals(expectedWire, LLMObsTraceId.toWire(hex));
  }

  @TableTest({
    "scenario       | wire                                      | expectedHex                       ",
    "zero           | '0'                                       | '00000000000000000000000000000000'",
    "one            | '1'                                       | '00000000000000000000000000000001'",
    "64-bit value   | '18446744073709551615'                    | '0000000000000000ffffffffffffffff'",
    "full 128 bits  | '340282366920938463463374607431768211455' | 'ffffffffffffffffffffffffffffffff'",
    "mixed          | '144943587637881175037429778545693296408' | '6d0b1e9c00000000a1b2c3d4e5f60718'",
    "already hex    | '6d0b1e9c00000000a1b2c3d4e5f60718'        | '6d0b1e9c00000000a1b2c3d4e5f60718'",
    "hex, leading 0 | '0000000000000000000000000000000a'        | '0000000000000000000000000000000a'"
  })
  void convertsTheDecimalTheWireCarriesToStoredHex(String wire, String expectedHex) {
    assertEquals(expectedHex, LLMObsTraceId.fromWire(wire));
  }

  /**
   * A round trip has to be lossless, because a service in the middle of a chain does both: it reads
   * the caller's id and writes that same id on to the next hop.
   */
  @TableTest({
    "scenario      | hex                               ",
    "zero          | '00000000000000000000000000000000'",
    "one           | '00000000000000000000000000000001'",
    "all digits    | '12345678901234567890123456789012'",
    "all letters   | 'abcdefabcdefabcdefabcdefabcdefab'",
    "full 128 bits | 'ffffffffffffffffffffffffffffffff'"
  })
  void roundTripsThroughTheWireUnchanged(String hex) {
    assertEquals(hex, LLMObsTraceId.fromWire(LLMObsTraceId.toWire(hex)));
  }

  /**
   * A 32-character run of digits is valid under both encodings, so the reader has to break the tie.
   * A decimal integer is never written with a leading zero and never contains {@code a-f}, so a
   * value that has either is hex and everything else is the decimal the wire is documented to
   * carry. Mirrors {@code _normalize_wire_trace_id_to_hex} in dd-trace-py, so that the two tracers
   * resolve the same value the same way.
   */
  @Test
  void readsAnAmbiguousAllDigitValueAsDecimal() {
    assertEquals(
        "0000009bd30a3c645943dd1690a03a14",
        LLMObsTraceId.fromWire("12345678901234567890123456789012"));
  }

  @Test
  void readsAnAmbiguousAllDigitValueWithALeadingZeroAsHex() {
    assertEquals(
        "01234567890123456789012345678901",
        LLMObsTraceId.fromWire("01234567890123456789012345678901"));
  }

  /**
   * An id the tracer did not generate — one an application set by hand, say — still has to reach
   * the backend, which is the only place that can decide what to do with it. Neither direction
   * throws and neither drops the value.
   */
  @TableTest({
    "scenario           | value                             ",
    "uppercase hex      | '6D0B1E9C00000000A1B2C3D4E5F60718'",
    "too short          | 'abcdef'                          ",
    "not a number       | 'my-custom-trace-id'              ",
    "decimal with a dot | '1.5'                             "
  })
  void leavesAValueItCannotInterpretUntouched(String value) {
    assertEquals(value, LLMObsTraceId.fromWire(value));
    assertEquals(value, LLMObsTraceId.toWire(value));
  }

  @Test
  void treatsNullAndEmptyAsAbsent() {
    assertNull(LLMObsTraceId.toWire(null));
    assertNull(LLMObsTraceId.fromWire(null));
    assertNull(LLMObsTraceId.toWire(""));
    assertNull(LLMObsTraceId.fromWire(""));
  }
}
