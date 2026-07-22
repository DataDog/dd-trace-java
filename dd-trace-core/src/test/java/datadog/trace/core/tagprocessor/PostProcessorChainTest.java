package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostProcessorChainTest extends DDJavaSpecification {

  @Test
  void chainWorks() {
    TagsPostProcessor processor1 =
        new TagsPostProcessor() {
          @Override
          public void processTags(
              TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
            unsafeTags.put("key1", "processor1");
            unsafeTags.put("key2", "processor1");
          }
        };
    TagsPostProcessor processor2 =
        new TagsPostProcessor() {
          @Override
          public void processTags(
              TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
            unsafeTags.put("key1", "processor2");
          }
        };

    PostProcessorChain chain = new PostProcessorChain(processor1, processor2);

    List<Object> links = new ArrayList<>();
    TagMap tags = TagMap.fromMap(linkedMap("key1", "overwrite", "key3", "unchanged"));

    chain.processTags(tags, null, link -> links.add(link));

    Map<String, Object> expected =
        linkedMap("key1", "processor2", "key2", "processor1", "key3", "unchanged");
    assertEquals(expected, tags);
    assertTrue(links.isEmpty());
  }

  @Test
  void processorCanHideTagsToNextOne() {
    TagsPostProcessor processor1 =
        new TagsPostProcessor() {
          @Override
          public void processTags(
              TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
            unsafeTags.clear();
            unsafeTags.put("my", "tag");
          }
        };
    TagsPostProcessor processor2 =
        new TagsPostProcessor() {
          @Override
          public void processTags(
              TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
            if (unsafeTags.containsKey("test")) {
              unsafeTags.put("found", "true");
            }
          }
        };

    PostProcessorChain chain = new PostProcessorChain(processor1, processor2);

    List<Object> links = new ArrayList<>();
    TagMap tags = TagMap.fromMap(linkedMap("test", "test"));

    chain.processTags(tags, null, link -> links.add(link));

    assertEquals(linkedMap("my", "tag"), tags);
    assertTrue(links.isEmpty());
  }

  private static LinkedHashMap<String, Object> linkedMap(Object... pairs) {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }
}
