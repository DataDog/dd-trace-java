package datadog.trace.core.propagation;

import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.util.Arrays;

/**
 * Single-pass, non-throwing parser for the W3C {@code traceparent} header: {@code
 * version-traceid-spanid-flags}. The header is attacker/misconfiguration-controlled and malformed
 * input is common in practice, so this avoids paying for exception construction on what is
 * effectively a validation failure, not an exceptional condition.
 *
 * <p>Validity and value extraction happen in the same scan; there's no intermediate result object
 * to avoid allocating (or hoping escape analysis elides it). Instead, a valid parse invokes a
 * caller-supplied {@link TraceparentHandler} directly with the parsed primitives.
 */
final class TraceparentParser {

  private static final int VERSION_LENGTH = 2;
  private static final int TID_START = VERSION_LENGTH + 1;
  private static final int TID_HI_LENGTH = 16;
  private static final int TID_LO_LENGTH = 16;
  private static final int TID_LENGTH = TID_HI_LENGTH + TID_LO_LENGTH;
  private static final int SID_START = TID_START + TID_LENGTH + 1;
  private static final int SID_LENGTH = 16;
  private static final int FLAGS_START = SID_START + SID_LENGTH + 1;
  private static final int FLAGS_LENGTH = 2;
  private static final int MIN_LENGTH = FLAGS_START + FLAGS_LENGTH;

  // Lower-case-only hex digit lookup: the W3C spec mandates lower-case hex for traceparent, so
  // upper-case letters are simply absent from the table rather than accepted and normalized.
  private static final byte[] HEX_DIGIT = buildHexDigitTable();

  private static byte[] buildHexDigitTable() {
    byte[] table = new byte[128];
    Arrays.fill(table, (byte) -1);
    for (int i = 0; i <= 9; i++) {
      table['0' + i] = (byte) i;
    }
    for (int i = 0; i <= 5; i++) {
      table['a' + i] = (byte) (10 + i);
    }
    return table;
  }

  private static int hexDigit(char c) {
    return c < HEX_DIGIT.length ? HEX_DIGIT[c] : -1;
  }

  private TraceparentParser() {}

  /** Receives the fields of a successfully parsed traceparent header. */
  @Strategy
  interface TraceparentHandler<C> {
    void onValid(C ctx, long traceIdHi, long traceIdLo, long spanId, int flags);
  }

  /**
   * Parses {@code tp} as a W3C traceparent header, calling {@code handler.onValid} with the parsed
   * fields on success. Returns {@code false} for any malformed input instead of throwing -
   * including a header whose version/trace-id/span-id/flags fields are individually well-formed hex
   * but whose trace-id or span-id is all-zero, which the spec also treats as invalid.
   */
  @StrategyConsumer
  static <C> boolean parse(String tp, C ctx, @Strategy TraceparentHandler<? super C> handler) {
    int length = tp == null ? 0 : tp.length();
    if (length < MIN_LENGTH) {
      return false;
    }

    int v0 = hexDigit(tp.charAt(0));
    int v1 = hexDigit(tp.charAt(1));
    if (v0 < 0 || v1 < 0) {
      return false;
    }
    int version = (v0 << 4) | v1;
    if (version == 0xFF) {
      return false;
    }
    if (version == 0 && length > MIN_LENGTH) {
      return false;
    }
    if (tp.charAt(VERSION_LENGTH) != '-') {
      return false;
    }

    long traceIdHi = 0;
    for (int i = 0; i < TID_HI_LENGTH; i++) {
      int d = hexDigit(tp.charAt(TID_START + i));
      if (d < 0) {
        return false;
      }
      traceIdHi = (traceIdHi << 4) | d;
    }
    int tidLoStart = TID_START + TID_HI_LENGTH;
    long traceIdLo = 0;
    for (int i = 0; i < TID_LO_LENGTH; i++) {
      int d = hexDigit(tp.charAt(tidLoStart + i));
      if (d < 0) {
        return false;
      }
      traceIdLo = (traceIdLo << 4) | d;
    }
    // Only the low-order 64 bits are checked for an all-zero trace id, matching the historical
    // (pre-128-bit) definition of an invalid trace id.
    if (traceIdLo == 0) {
      return false;
    }
    if (tp.charAt(TID_START + TID_LENGTH) != '-') {
      return false;
    }

    long spanId = 0;
    for (int i = 0; i < SID_LENGTH; i++) {
      int d = hexDigit(tp.charAt(SID_START + i));
      if (d < 0) {
        return false;
      }
      spanId = (spanId << 4) | d;
    }
    if (spanId == 0) {
      return false;
    }
    if (tp.charAt(SID_START + SID_LENGTH) != '-') {
      return false;
    }

    int f0 = hexDigit(tp.charAt(FLAGS_START));
    int f1 = hexDigit(tp.charAt(FLAGS_START + 1));
    if (f0 < 0 || f1 < 0) {
      return false;
    }
    int flags = (f0 << 4) | f1;

    if (version != 0 && length > MIN_LENGTH && tp.charAt(MIN_LENGTH) != '-') {
      return false;
    }

    handler.onValid(ctx, traceIdHi, traceIdLo, spanId, flags);
    return true;
  }
}
