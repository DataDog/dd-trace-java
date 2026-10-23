package datadog.trace.core.propagation.ptags;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.core.propagation.PropagationTags;
import org.junit.jupiter.api.Test;

/** Values unique to a trace or span skip the tag value cache; values that repeat use it. */
class UniqueTagValueCachingTest {
  private static final PropagationTags.Factory FACTORY = PropagationTags.factory();

  @Test
  void lastParentIdIsNotCached() {
    CharSequence first =
        FACTORY.fromHeaderValue(W3C, "dd=s:1;p:00f067aa0ba902b7").getLastParentId();
    CharSequence second =
        FACTORY.fromHeaderValue(W3C, "dd=s:1;p:00f067aa0ba902b7").getLastParentId();

    assertEquals(first, second);
    assertNotSame(first, second);
  }

  @Test
  void uncachedValueEqualsCachedValue() {
    TagValue cached = TagValue.from(TagElement.Encoding.W3C, "x:abc", 2, 5);
    TagValue uncached = TagValue.uncached(TagElement.Encoding.W3C, "x:abc", 2, 5);

    assertEquals(cached, uncached);
    assertEquals(cached.hashCode(), uncached.hashCode());
    assertEquals(
        cached.forType(TagElement.Encoding.DATADOG), uncached.forType(TagElement.Encoding.DATADOG));
  }

  @Test
  void repeatedValuesAreStillCached() {
    TagValue first = TagValue.from(TagElement.Encoding.DATADOG, "-4");
    TagValue second = TagValue.from(TagElement.Encoding.DATADOG, "-4");

    assertSame(first, second);
  }

  @Test
  void llmObsIdsParseTheSameUncached() {
    String header = "_dd.p.llmobs_trace_id=0123456789abcdef0123456789abcdef,_dd.p.dm=-1";

    PropagationTags tags = FACTORY.fromHeaderValue(DATADOG, header);

    assertEquals(
        "0123456789abcdef0123456789abcdef",
        tags.createTagMap().get("_dd.p.llmobs_trace_id"),
        tags.createTagMap().toString());
  }
}
