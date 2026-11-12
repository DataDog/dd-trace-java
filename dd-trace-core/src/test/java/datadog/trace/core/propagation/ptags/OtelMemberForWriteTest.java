package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
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
        CharSequence parsed = state(member, checks).getOtelTraceState();
        CharSequence written = state(member, checks).getOtelTraceStateForWrite();
        assertEquals(
            parsed == null ? null : parsed.toString(),
            written == null ? null : written.toString(),
            member + " checks=" + checks);
      }
    }
  }

  @Test
  void unchangedMemberIsWrittenRawWithoutParsing() {
    InheritedSamplingState consistent = state("rv:c8b1e2d3f4a596;th:8", sampledCheck());
    CharSequence written = consistent.getOtelTraceStateForWrite();
    assertTrue(written instanceof SubSequence, String.valueOf(written));
    assertFalse(consistent.isResolved());

    InheritedSamplingState inconsistent = state("rv:c8b1e2d3f4a596;th:8", notSampledCheck());
    assertTrue(inconsistent.getOtelTraceStateForWrite() instanceof OtelTraceState);
  }

  @Test
  void parsedMemberIsWrittenAsParsed() {
    InheritedSamplingState state = state("rv:c8b1e2d3f4a596;th:8", sampledCheck());
    CharSequence parsed = state.getOtelTraceState();
    assertSame(parsed, state.getOtelTraceStateForWrite());
  }

  private static byte sampledCheck() {
    return InheritedSamplingState.withCheck(InheritedSamplingState.NO_CHECKS, true);
  }

  private static byte notSampledCheck() {
    return InheritedSamplingState.withCheck(InheritedSamplingState.NO_CHECKS, false);
  }

  private static InheritedSamplingState state(String member, int checks) {
    byte pending = InheritedSamplingState.NO_CHECKS;
    if ((checks & 1) != 0) {
      pending = InheritedSamplingState.withCheck(pending, true);
    }
    if ((checks & 2) != 0) {
      pending = InheritedSamplingState.withCheck(pending, false);
    }
    return state(member, pending);
  }

  private static InheritedSamplingState state(String member, byte pending) {
    String tracestate = "dd=s:1,ot=" + member;
    int start = tracestate.indexOf("ot=") + 3;
    return new InheritedSamplingState(
        SAMPLER_KEEP,
        tracestate,
        null,
        null,
        SubSequence.of(tracestate, start, tracestate.length()),
        Objects.requireNonNull(member).length() + 4,
        pending);
  }
}
