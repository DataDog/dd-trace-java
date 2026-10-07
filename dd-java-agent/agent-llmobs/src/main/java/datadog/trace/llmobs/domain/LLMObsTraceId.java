package datadog.trace.llmobs.domain;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.internal.util.LongStringUtils;
import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts the LLM Observability trace id between the form the tracer stores and the form the
 * {@code _dd.p.llmobs_trace_id} propagation tag carries.
 *
 * <p>The two differ. On the wire an LLMObs trace id is an unsigned decimal integer, because
 * released dd-trace-py versions parse the tag with {@code int(x)} and would reject {@code a-f}.
 * In-process, and in the span payload the intake receives, it is a 32-character lowercase
 * hexadecimal string — <em>except</em> below 2^64, where it is unsigned decimal instead. That
 * exception matches dd-trace-py and dd-trace-js, which both store the smaller ids as decimal; the
 * backend groups a trace by comparing the stored value verbatim, so all three have to agree.
 *
 * <p>The methods here mirror {@code format_trace_id}, {@code _trace_id_to_wire} and {@code
 * _normalize_wire_trace_id_to_hex} in dd-trace-py's {@code llmobs/_utils.py}, including their
 * handling of a value that is neither hex nor decimal — an id a user set by hand, say — which
 * passes through untouched rather than being rejected.
 */
public final class LLMObsTraceId {
  private static final Logger LOGGER = LoggerFactory.getLogger(LLMObsTraceId.class);

  /** Length of an LLMObs trace id in hexadecimal, matching a 128-bit APM trace id. */
  private static final int HEX_LENGTH = 32;

  private LLMObsTraceId() {}

  /**
   * Renders an APM trace id in the form an LLMObs trace id seeded from it is stored in: hexadecimal
   * above 2^64, unsigned decimal below it.
   *
   * <p>Mirrors {@code format_trace_id} in dd-trace-py and the root-storage rule in dd-trace-js. A
   * zero high-order long is exactly dd-trace-py's {@code trace_id > MAX_UINT_64BITS} test, and when
   * it is zero the low-order long holds the whole value, so {@link DDTraceId#toString()} — which
   * renders only the low 64 bits — is the right decimal. It is not safe to call outside this
   * branch, which is the reason to go through here rather than at each call site.
   */
  static String format(DDTraceId traceId) {
    return traceId.toHighOrderLong() != 0 ? traceId.toHexString() : traceId.toString();
  }

  /**
   * Converts a stored hexadecimal trace id to the decimal form the propagation tag carries. Returns
   * {@code null} for a null or empty input, and returns anything that is not canonical hex
   * unchanged.
   */
  public static String toWire(String traceId) {
    if (traceId == null || traceId.isEmpty()) {
      return null;
    }
    if (!isCanonicalHex(traceId)) {
      return traceId;
    }
    try {
      return new BigInteger(traceId, 16).toString();
    } catch (NumberFormatException e) {
      LOGGER.debug("failed to convert hex LLMObs trace_id {} to decimal, sending as-is", traceId);
      return traceId;
    }
  }

  /**
   * Converts a trace id that arrived on the propagation tag to the form used in-process and in the
   * span payload: hexadecimal above 2^64, unsigned decimal below it. Returns {@code null} for a
   * null or empty input, and returns a value that is neither canonical hex nor a decimal integer
   * unchanged.
   *
   * <p>A 32-digit value is ambiguous — it reads as both hex and decimal. It is resolved as hex only
   * when it could not have been produced by the decimal encoding: a decimal integer never carries a
   * leading zero, and a value containing {@code a-f} is not decimal at all.
   *
   * <p>Leaving a sub-2^64 value decimal is what keeps a Java continuation agreeing with the tracer
   * that rooted the trace, whichever language that was — including Java itself, whose roots store
   * the same form through {@link #format}. Converting it to hex here instead would make every
   * 64-bit trace that crosses a process boundary arrive at the backend as two.
   */
  public static String fromWire(String traceId) {
    if (traceId == null || traceId.isEmpty()) {
      return null;
    }
    if (isCanonicalHex(traceId) && (traceId.charAt(0) == '0' || !isDecimal(traceId))) {
      return traceId;
    }
    if (isDecimal(traceId)) {
      try {
        BigInteger value = new BigInteger(traceId);
        // Re-rendered rather than passed through, so that an id written with leading zeros stores
        // the same string as the same id written without them. dd-trace-py's str(int(value)) and
        // dd-trace-js's BigInt round trip both normalise it the same way.
        return value.bitLength() > 64 ? toHex(value) : value.toString();
      } catch (NumberFormatException e) {
        // Unreachable for an all-digit string, but a malformed id must never fail a span.
        LOGGER.debug("failed to normalise decimal LLMObs trace_id {}, storing as-is", traceId);
        return traceId;
      }
    }
    LOGGER.debug(
        "LLMObs trace_id {} is neither canonical hex nor a decimal integer, storing as-is",
        traceId);
    return traceId;
  }

  /**
   * Renders an unsigned integer as lowercase hex, left-padded to {@link #HEX_LENGTH}.
   *
   * <p>A value wider than 128 bits is rendered at its natural width rather than truncated, so it
   * stays whatever the caller sent. Truncating would fit the id to {@link #HEX_LENGTH} and make it
   * canonical, which is worse: the id would silently become a different one that nothing downstream
   * could tell apart from a real one. Matches {@code format_trace_id} in dd-trace-py, which pads
   * rather than truncates for the same reason.
   */
  private static String toHex(BigInteger value) {
    if (value.bitLength() > 128) {
      return value.toString(16);
    }
    return LongStringUtils.toHexStringPadded(
        value.shiftRight(64).longValue(), value.longValue(), HEX_LENGTH);
  }

  /** Whether the value is exactly {@link #HEX_LENGTH} lowercase hexadecimal digits. */
  private static boolean isCanonicalHex(String value) {
    if (value.length() != HEX_LENGTH) {
      return false;
    }
    for (int i = 0; i < HEX_LENGTH; i++) {
      char c = value.charAt(i);
      if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
        return false;
      }
    }
    return true;
  }

  /** Whether the value is a non-empty run of ASCII digits. */
  private static boolean isDecimal(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }
}
