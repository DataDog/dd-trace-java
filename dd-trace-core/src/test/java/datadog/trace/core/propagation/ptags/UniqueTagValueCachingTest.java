package datadog.trace.core.propagation.ptags;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.llmobs.LLMObsPropagationValues;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Map;
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
  void llmObsIdsAreNotCachedButOtherLlmObsValuesAre() {
    String id = "x0123456789abcdef";
    String mlApp = "xchatbot";

    assertNotSame(
        PTagsCodec.tagValue(TagElement.Encoding.DATADOG, PTagsCodec.LLMOBS_TRACE_ID_TAG, id, 1, 17),
        PTagsCodec.tagValue(
            TagElement.Encoding.DATADOG, PTagsCodec.LLMOBS_TRACE_ID_TAG, id, 1, 17));
    assertSame(
        PTagsCodec.tagValue(TagElement.Encoding.DATADOG, PTagsCodec.LLMOBS_ML_APP_TAG, mlApp, 1, 8),
        PTagsCodec.tagValue(
            TagElement.Encoding.DATADOG, PTagsCodec.LLMOBS_ML_APP_TAG, mlApp, 1, 8));
  }

  @Test
  void rootParentMarkerIsStillCached() {
    String header = "xundefined";

    assertSame(
        PTagsCodec.tagValue(
            TagElement.Encoding.DATADOG, PTagsCodec.LLMOBS_PARENT_ID_TAG, header, 1, 10),
        PTagsCodec.tagValue(
            TagElement.Encoding.DATADOG, PTagsCodec.LLMOBS_PARENT_ID_TAG, header, 1, 10));
  }

  @Test
  void injectedLlmObsIdsAreNotCachedButTheRootMarkerAndOtherValuesAre() {
    LLMObsTagValues first = LLMObsTagValues.from(injected("0123456789abcdef", "4567"));
    LLMObsTagValues second = LLMObsTagValues.from(injected("0123456789abcdef", "4567"));

    assertEquals(first.traceId, second.traceId);
    assertNotSame(first.traceId, second.traceId);
    assertNotSame(first.parentId, second.parentId);
    assertNotSame(first.parentAgentSpanId, second.parentAgentSpanId);
    assertSame(first.mlApp, second.mlApp);

    LLMObsTagValues root = LLMObsTagValues.from(injected("0123456789abcdef", "undefined"));
    LLMObsTagValues otherRoot = LLMObsTagValues.from(injected("fedcba9876543210", "undefined"));
    assertSame(root.parentId, otherRoot.parentId);
  }

  private static LLMObsPropagationValues injected(String traceId, String parentId) {
    return new LLMObsPropagationValues(
        traceId, "chatbot", null, parentId + "1", null, parentId, null, null);
  }

  @Test
  void llmObsIdsParseTheSame() {
    String header = "_dd.p.llmobs_trace_id=0123456789abcdef0123456789abcdef,_dd.p.dm=-1";

    Map<String, String> tagMap = FACTORY.fromHeaderValue(DATADOG, header).createTagMap();

    assertEquals(
        "0123456789abcdef0123456789abcdef", tagMap.get("_dd.p.llmobs_trace_id"), tagMap::toString);
  }
}
