package datadog.trace.llmobs.domain;

import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts the LLM Observability trace id between the form the tracer stores and the form the
 * {@code _dd.p.llmobs_trace_id} propagation tag carries.
 *
 * <p>The two differ. In-process, and in the span payload the intake receives, an LLMObs trace id is
 * a 32-character lowercase hexadecimal string — the same shape as a 128-bit APM trace id, which is
 * what a Java-only trace seeds it from. On the wire it is an unsigned decimal integer, because
 * released dd-trace-py versions parse the tag with {@code int(x)} and would reject {@code a-f}.
 * Every other tracer follows that contract, so Java has to as well.
 *
 * <p>The methods here mirror {@code _trace_id_to_wire} and {@code _normalize_wire_trace_id_to_hex}
 * in dd-trace-py's {@code llmobs/_utils.py}, including their handling of a value that is neither —
 * an id a user set by hand, say — which passes through untouched rather than being rejected.
 */
public final class LLMObsTraceId {
  private static final Logger LOGGER = LoggerFactory.getLogger(LLMObsTraceId.class);

  /** Length of an LLMObs trace id in hexadecimal, matching a 128-bit APM trace id. */
  private static final int HEX_LENGTH = 32;

  private LLMObsTraceId() {}

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
   * Converts a trace id that arrived on the propagation tag to the hexadecimal form used in-process
   * and in the span payload. Returns {@code null} for a null or empty input, and returns a value
   * that is neither canonical hex nor a decimal integer unchanged.
   *
   * <p>A 32-digit value is ambiguous — it reads as both hex and decimal. It is resolved as hex only
   * when it could not have been produced by the decimal encoding: a decimal integer never carries a
   * leading zero, and a value containing {@code a-f} is not decimal at all.
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
        return toHex(new BigInteger(traceId));
      } catch (NumberFormatException e) {
        // Unreachable for an all-digit string, but a malformed id must never fail a span.
        LOGGER.debug("failed to convert decimal LLMObs trace_id {} to hex, storing as-is", traceId);
        return traceId;
      }
    }
    LOGGER.debug(
        "LLMObs trace_id {} is neither canonical hex nor a decimal integer, storing as-is",
        traceId);
    return traceId;
  }

  /** Renders an unsigned integer as lowercase hex, left-padded to {@link #HEX_LENGTH}. */
  private static String toHex(BigInteger value) {
    String hex = value.toString(16);
    if (hex.length() >= HEX_LENGTH) {
      return hex;
    }
    StringBuilder padded = new StringBuilder(HEX_LENGTH);
    for (int i = hex.length(); i < HEX_LENGTH; i++) {
      padded.append('0');
    }
    return padded.append(hex).toString();
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
