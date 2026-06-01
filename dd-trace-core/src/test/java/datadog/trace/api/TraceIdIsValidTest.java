package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.core.propagation.B3TraceId;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link DDTraceId#isValid()} across every {@link DDTraceId} subtype, including the {@code ZERO}/
 * {@code ONE} sibling constants and {@link B3TraceId} (defined in dd-trace-core).
 *
 * <p>{@code isValid()} is value-based: a zero id is invalid regardless of its concrete type or how
 * it was created (a zero parsed via the 64-bit factories is a distinct instance from {@code ZERO}).
 */
class TraceIdIsValidTest {

  static Stream<Arguments> traceIds() {
    return Stream.of(
        // Zero-valued ids of every type are invalid. A label is supplied because several distinct
        // cases share the same toString() ("0"), so the value alone would not name them uniquely.
        Arguments.of("ZERO constant", DDTraceId.ZERO, false),
        Arguments.of("DDTraceId.from(0)", DDTraceId.from(0), false),
        Arguments.of("DDTraceId.from(\"0\")", DDTraceId.from("0"), false),
        Arguments.of("DDTraceId.fromHex(\"0\")", DDTraceId.fromHex("0"), false),
        Arguments.of("DD64bTraceId.from(0)", DD64bTraceId.from(0), false),
        Arguments.of("DD128bTraceId.from(0, 0)", DD128bTraceId.from(0, 0), false),
        Arguments.of("B3TraceId.fromHex(\"0\")", B3TraceId.fromHex("0"), false),
        // Non-zero ids are valid.
        Arguments.of("ONE constant", DDTraceId.ONE, true),
        Arguments.of("DDTraceId.from(1)", DDTraceId.from(1), true),
        Arguments.of("DD64bTraceId.from(42)", DD64bTraceId.from(42), true),
        Arguments.of("DD128bTraceId.from(0, 1)", DD128bTraceId.from(0, 1), true),
        // High-order bits set, low-order zero: still a valid TraceId.
        Arguments.of("DD128bTraceId.from(7, 0)", DD128bTraceId.from(7, 0), true),
        Arguments.of("B3TraceId.fromHex(\"2a\")", B3TraceId.fromHex("2a"), true));
  }

  @ParameterizedTest(name = "{0} isValid={2}")
  @MethodSource("traceIds")
  void isValidReflectsTheValue(String label, DDTraceId traceId, boolean expectedValid) {
    assertEquals(expectedValid, traceId.isValid(), label);
  }
}
