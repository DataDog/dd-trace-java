package datadog.trace.core.propagation.ptags;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.propagation.PropagationTags;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** An extracted x-datadog-tags value is injected as it arrived while nothing about it changed. */
class XDatadogTagsPassthroughTest {
  private static final PropagationTags.Factory FACTORY = PropagationTags.factory();

  /** The header an unchanged trace injects, read the way the Datadog injector reads it. */
  private static String injected(PropagationTags ptags) {
    return ptags.headerValue(DATADOG, null, ptags.samplingState());
  }

  @TableTest({
    "scenario              | header                                           ",
    "decision maker only   | '_dd.p.dm=-1'                                    ",
    "encoder's order       | '_dd.p.dm=-1,_dd.p.tid=640cfd8d00000000'         ",
    "another SDK's order   | '_dd.p.tid=640cfd8d00000000,_dd.p.dm=-1'         ",
    "with other _dd.p tags | '_dd.p.dm=-4,_dd.p.usr.id=abc,_dd.p.anytag=value'",
    "with trace source     | '_dd.p.dm=-1,_dd.p.ts=02'                        "
  })
  void sendsAVerbatimHeaderAsItArrived(String header) {
    // a fresh instance, so assertSame shows the parsed value itself is what's sent
    String raw = new String(header.toCharArray());
    PropagationTags ptags = FACTORY.fromHeaderValue(DATADOG, raw);

    assertSame(raw, injected(ptags));
    assertSame(raw, injected(ptags), "a second injection reuses it too");
  }

  @TableTest({
    "scenario                | header                                  | expected     ",
    "non-propagated key      | 'foo=bar,_dd.p.dm=-4'                   | '_dd.p.dm=-4'",
    "deprecated upstream     | '_dd.p.upstream_services=x,_dd.p.dm=-4' | '_dd.p.dm=-4'",
    "repeated decision maker | '_dd.p.dm=-1,_dd.p.dm=-4'               | '_dd.p.dm=-4'"
  })
  void encodesAHeaderThatLostOrMergedMembers(String header, String expected) {
    PropagationTags ptags = FACTORY.fromHeaderValue(DATADOG, header);

    String injected = injected(ptags);
    assertNotSame(header, injected);
    assertEquals(expected, injected);
  }

  @Test
  void encodesAgainOnceTheSamplingDecisionChanges() {
    String raw = "_dd.p.anytag=value";
    PropagationTags ptags = FACTORY.fromHeaderValue(DATADOG, raw);
    assertSame(raw, injected(ptags));

    // no upstream decision maker, so this decision sets one
    ptags.updateTraceSamplingPriority(PrioritySampling.SAMPLER_KEEP, SamplingMechanism.AGENT_RATE);

    assertEquals("_dd.p.dm=-1,_dd.p.anytag=value", injected(ptags));
  }

  @Test
  void sendsItAsItArrivedAfterATracestateMerge() {
    // Extracting both formats merges the W3C tracestate into the Datadog context, which replaces
    // the sampling state without touching anything x-datadog-tags carries.
    String raw = new String("_dd.p.tid=640cfd8d00000000,_dd.p.dm=-1".toCharArray());
    PropagationTags ptags = FACTORY.fromHeaderValue(DATADOG, raw);

    ptags.updateW3CTracestate("dd=s:1;p:00f067aa0ba902b7;t.dm:-1,ot=th:0;rv:e1");

    assertSame(raw, injected(ptags));
  }

  @Test
  void injectsNothingForAnEmptyHeader() {
    assertNull(injected(FACTORY.fromHeaderValue(DATADOG, "")));
  }
}
