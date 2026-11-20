package datadog.trace.core.datastreams;

import static datadog.trace.api.DDTags.PATHWAY_HASH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class PathwayHashTagTest {
  private static AgentSpan spanWithPriority(int priority) {
    AgentSpanContext context = mock(AgentSpanContext.class);
    when(context.getSamplingPriority()).thenReturn(priority);
    AgentSpan span = mock(AgentSpan.class);
    when(span.spanContext()).thenReturn(context);
    return span;
  }

  @Test
  void cachedStringsEqualLongToUnsignedString() {
    Random random = new Random(7);
    for (int i = 0; i < 10_000; i++) {
      long hash = random.nextLong();
      assertEquals(Long.toUnsignedString(hash), PathwayHashTag.toUnsignedString(hash));
    }
    long hash = -1234567890123L;
    assertSame(PathwayHashTag.toUnsignedString(hash), PathwayHashTag.toUnsignedString(hash));
  }

  @TableTest({
    "scenario     | priority | sampledOnly | tagged",
    "user drop    | -1       | true        | false ",
    "sampler drop | 0        | true        | false ",
    "sampler keep | 1        | true        | true  ",
    "user keep    | 2        | true        | true  ",
    "undecided    | -128     | true        | true  ",
    "all, dropped | 0        | false       | true  "
  })
  void sampledOnlySkipsSpansWhoseTraceIsDropped(int priority, boolean sampledOnly, boolean tagged) {
    AgentSpan span = spanWithPriority(priority);

    PathwayHashTag.set(span, -42L, sampledOnly);

    if (tagged) {
      verify(span).setTag(PATHWAY_HASH, Long.toUnsignedString(-42L));
    } else {
      verify(span, never()).setTag(PATHWAY_HASH, Long.toUnsignedString(-42L));
    }
  }

  @Test
  void aZeroHashIsNeverTagged() {
    AgentSpan span = spanWithPriority(PrioritySampling.SAMPLER_KEEP);

    PathwayHashTag.set(span, 0, false);

    verify(span, never()).setTag(PATHWAY_HASH, "0");
  }
}
