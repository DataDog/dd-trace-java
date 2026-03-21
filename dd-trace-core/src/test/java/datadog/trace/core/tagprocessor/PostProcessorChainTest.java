package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostProcessorChainTest {

  @Test
  void chainWorks() {
    TagsPostProcessor processor1 =
        new TagsPostProcessor() {
          @Override
          public void processTags(
              TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
            unsafeTags.put("key1", "processor1");
            unsafeTags.put("key2", "processor1");
          }
        };
    TagsPostProcessor processor2 =
        new TagsPostProcessor() {
          @Override
          public void processTags(
              TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
            unsafeTags.put("key1", "processor2");
          }
        };

    PostProcessorChain chain = new PostProcessorChain(processor1, processor2);
    TagMap tags =
        TagMap.fromMap(
            new java.util.LinkedHashMap<String, Object>() {
              {
                put("key1", "root");
                put("key3", "root");
              }
            });

    chain.processTags(tags, null, Collections.<AgentSpanLink>emptyList());

    assertEquals("processor2", tags.get("key1"));
    assertEquals("processor1", tags.get("key2"));
    assertEquals("root", tags.get("key3"));
  }

  @Test
  void processorCanHideTagsToNextOne() {
    TagsPostProcessor processor1 =
        new TagsPostProcessor() {
          @Override
          public void processTags(
              TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
            unsafeTags.clear();
            unsafeTags.put("my", "tag");
          }
        };
    TagsPostProcessor processor2 =
        new TagsPostProcessor() {
          @Override
          public void processTags(
              TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
            if (unsafeTags.containsKey("test")) {
              unsafeTags.put("found", "true");
            }
          }
        };

    PostProcessorChain chain = new PostProcessorChain(processor1, processor2);
    TagMap tags = TagMap.fromMap(Collections.<String, Object>singletonMap("test", "test"));

    chain.processTags(tags, null, Collections.<AgentSpanLink>emptyList());

    assertEquals(1, tags.size());
    assertEquals("tag", tags.get("my"));
    assertFalse(tags.containsKey("found"));
    assertFalse(tags.containsKey("test"));
  }
}
