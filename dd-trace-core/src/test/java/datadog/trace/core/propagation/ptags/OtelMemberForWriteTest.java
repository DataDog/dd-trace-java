package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.SubSequence;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Writing an unparsed {@code ot} member gives the same content as parsing it and applying its
 * pending checks, and skips the parse when the member would come out unchanged.
 */
class OtelMemberForWriteTest {
  private static final String[] MEMBERS = {
    "rv:c8b1e2d3f4a596;th:8",
    "th:8;rv:c8b1e2d3f4a596",
    "rv:0000000000000a;th:8",
    "rv:c8b1e2d3f4a596;th:0",
    "rv:c8b1e2d3f4a596",
    "th:8",
    "rv:c8b1e2d3f4a596;th:8;x:y",
    "x:y",
    // normalized or rejected by the parser
    "rv:C8B1E2D3F4A596;th:8",
    "rv:c8b1;th:8",
    "rv:c8b1e2d3f4a596;rv:c8b1e2d3f4a596",
    "th:8;th:4",
    "th:xyz",
    "rv:c8b1e2d3f4a596;;th:8",
    "garbage",
    "rv:zz"
  };

  @Test
  void writtenContentMatchesTheParsedMember() {
    for (String member : MEMBERS) {
      for (int checks = 0; checks < 4; ++checks) {
        CharSequence parsed = member(member).otelTraceState(pending(checks));
        CharSequence written = member(member).otelTraceStateForWrite(pending(checks));
        assertEquals(
            parsed == null ? null : parsed.toString(),
            written == null ? null : written.toString(),
            member + " checks=" + checks);
      }
    }
  }

  @Test
  void unchangedMemberIsWrittenRawWithoutParsing() {
    W3CTracestate consistent = member("rv:c8b1e2d3f4a596;th:8");
    CharSequence written = consistent.otelTraceStateForWrite(sampledCheck());
    assertTrue(written instanceof SubSequence, String.valueOf(written));
    assertFalse(consistent.isResolved(sampledCheck()));

    W3CTracestate inconsistent = member("rv:c8b1e2d3f4a596;th:8");
    assertTrue(inconsistent.otelTraceStateForWrite(notSampledCheck()) instanceof OtelTraceState);
  }

  @Test
  void parsedMemberIsWrittenAsParsed() {
    W3CTracestate member = member("rv:c8b1e2d3f4a596;th:8");
    CharSequence parsed = member.otelTraceState(sampledCheck());
    assertSame(parsed, member.otelTraceStateForWrite(sampledCheck()));
  }

  private static byte sampledCheck() {
    return InheritedSamplingState.withCheck(InheritedSamplingState.NO_CHECKS, true);
  }

  private static byte notSampledCheck() {
    return InheritedSamplingState.withCheck(InheritedSamplingState.NO_CHECKS, false);
  }

  private static byte pending(int checks) {
    byte pending = InheritedSamplingState.NO_CHECKS;
    if ((checks & 1) != 0) {
      pending = InheritedSamplingState.withCheck(pending, true);
    }
    if ((checks & 2) != 0) {
      pending = InheritedSamplingState.withCheck(pending, false);
    }
    return pending;
  }

  private static W3CTracestate member(String member) {
    String tracestate = "dd=s:1,ot=" + member;
    int start = tracestate.indexOf("ot=") + 3;
    return new W3CTracestate(
        tracestate,
        SubSequence.of(tracestate, start, tracestate.length()),
        Objects.requireNonNull(member).length() + 4,
        UNSET,
        null);
  }
}
