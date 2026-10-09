package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDTraceId;
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

  /**
   * Above 2^64 the wire's decimal becomes hex; below it, it stays decimal. The split is
   * dd-trace-py's {@code format_trace_id} rule, which dd-trace-js also implements — the stored form
   * is what the backend groups a trace by, so all three have to resolve one id to one string.
   */
  @TableTest({
    "scenario       | wire                                      | expectedStored                    ",
    "zero           | '0'                                       | '0'                               ",
    "one            | '1'                                       | '1'                               ",
    "2^64 minus 1   | '18446744073709551615'                    | '18446744073709551615'            ",
    "2^64           | '18446744073709551616'                    | '00000000000000010000000000000000'",
    "full 128 bits  | '340282366920938463463374607431768211455' | 'ffffffffffffffffffffffffffffffff'",
    "mixed          | '144943587637881175037429778545693296408' | '6d0b1e9c00000000a1b2c3d4e5f60718'",
    "already hex    | '6d0b1e9c00000000a1b2c3d4e5f60718'        | '6d0b1e9c00000000a1b2c3d4e5f60718'",
    "hex, leading 0 | '0000000000000000000000000000000a'        | '0000000000000000000000000000000a'"
  })
  void storesAWireValueInTheShapeTheOtherTracersUse(String wire, String expectedStored) {
    assertEquals(expectedStored, LLMObsTraceId.fromWire(wire));
  }

  /** A decimal id keeps one stored spelling however the sender wrote it. */
  @TableTest({
    "scenario           | wire      | expectedStored",
    "leading zero       | '007'     | '7'           ",
    "many leading zeros | '0000042' | '42'          "
  })
  void normalisesLeadingZerosOnADecimalItKeepsAsDecimal(String wire, String expectedStored) {
    assertEquals(expectedStored, LLMObsTraceId.fromWire(wire));
  }

  /**
   * The wire value has to survive a pass-through hop, because a service in the middle of a chain
   * does both: it reads the caller's id and writes that same id on to the next hop. This is the
   * round trip that has to be lossless. Hex is <em>not</em> preserved below 2^64 — a stored {@code
   * 00..01} comes back as {@code 1} — which is why the stored form is produced by {@link
   * LLMObsTraceId#format} rather than by padding, so that the two always agree.
   */
  @TableTest({
    "scenario      | wire                                     ",
    "zero          | '0'                                      ",
    "one           | '1'                                      ",
    "2^64 minus 1  | '18446744073709551615'                   ",
    "2^64          | '18446744073709551616'                   ",
    "full 128 bits | '340282366920938463463374607431768211455'",
    "custom id     | 'my-custom-trace-id'                     "
  })
  void carriesAWireValueThroughAPassThroughHopUnchanged(String wire) {
    assertEquals(wire, LLMObsTraceId.toWire(LLMObsTraceId.fromWire(wire)));
  }

  /**
   * The property the whole split exists to guarantee: a root and a continuation of the same trace
   * store the same string, so the backend sees one trace. Checked either side of the 2^64 boundary,
   * since that is where the two spellings meet.
   */
  @TableTest({
    "scenario   | high | low ",
    "zero       | 0    | 0   ",
    "one        | 0    | 1   ",
    "64-bit id  | 0    | 4242",
    "128-bit id | 7    | 4242"
  })
  void storesTheSameIdAsARootAndAsAContinuation(long high, long low) {
    DDTraceId apmTraceId = DD128bTraceId.from(high, low);

    String atTheRoot = LLMObsTraceId.format(apmTraceId);
    String atTheContinuation = LLMObsTraceId.fromWire(LLMObsTraceId.toWire(atTheRoot));

    assertEquals(atTheRoot, atTheContinuation);
  }

  /**
   * A 64-bit APM trace id reaches {@link LLMObsTraceId#format} as a {@code DD64bTraceId}, whose
   * {@code toHexString()} pads to 32 characters. The decimal branch has to be chosen off the
   * high-order long rather than off the rendered width, or that padding would silently make every
   * 64-bit id hex again.
   */
  @Test
  void formatsA64BitApmTraceIdAsDecimalDespiteItsPaddedHex() {
    DDTraceId apmTraceId = DDTraceId.from(4242);

    assertEquals("00000000000000000000000000001092", apmTraceId.toHexString());
    assertEquals("4242", LLMObsTraceId.format(apmTraceId));
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

  /**
   * A decimal id that does not fit in 128 bits is kept at its natural width rather than truncated
   * to 32 characters. Truncating would produce a canonical-looking id that is not the one the
   * caller sent — 2^128 would arrive as 32 zeros — and nothing downstream could tell it apart from
   * a real id. Passing it through leaves a value that fails the canonical check instead, which is
   * also what dd-trace-py's {@code format_trace_id} does.
   */
  @TableTest({
    "scenario     | wire                                      | expectedHex                        ",
    "2^128        | '340282366920938463463374607431768211456' | '100000000000000000000000000000000'",
    "2^128 plus 9 | '340282366920938463463374607431768211465' | '100000000000000000000000000000009'"
  })
  void keepsADecimalWiderThan128BitsRatherThanTruncatingIt(String wire, String expectedHex) {
    assertEquals(expectedHex, LLMObsTraceId.fromWire(wire));
  }

  @Test
  void treatsNullAndEmptyAsAbsent() {
    assertNull(LLMObsTraceId.toWire(null));
    assertNull(LLMObsTraceId.fromWire(null));
    assertNull(LLMObsTraceId.toWire(""));
    assertNull(LLMObsTraceId.fromWire(""));
  }
}
