package datadog.trace.core.tagprocessor

import datadog.trace.api.TagMap
import datadog.trace.bootstrap.instrumentation.api.WritableSpanLinks
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

class PostProcessorChainTest extends DDSpecification {
  def "chain works"() {
    setup:
    def processor1 = new TagsPostProcessor() {
        @Override
        void processTags(TagMap unsafeTags, DDSpanContext spanContext, WritableSpanLinks spanLinks) {
          unsafeTags.put("key1", "processor1")
          unsafeTags.put("key2", "processor1")
        }
      }
    def processor2 = new TagsPostProcessor() {
        @Override
        void processTags(TagMap unsafeTags, DDSpanContext spanContext, WritableSpanLinks spanLinks) {
          unsafeTags.put("key1", "processor2")
        }
      }

    def chain = new PostProcessorChain(processor1, processor2)
    
    def links = []
    def tags = TagMap.fromMap(["key1": "overwrite", "key3": "unchanged"])

    when:
    chain.processTags(tags, null, {link -> links.add(link)});

    then:
    assert tags == ["key1": "processor2", "key2": "processor1", "key3": "unchanged"]
    assert links == []
  }

  def "processor can hide tags to next one()"() {
    setup:
    def processor1 = new TagsPostProcessor() {
        @Override
        void processTags(TagMap unsafeTags, DDSpanContext spanContext, WritableSpanLinks spanLinks) {
          unsafeTags.clear()
          unsafeTags.put("my", "tag")
        }
      }
    def processor2 = new TagsPostProcessor() {
        @Override
        void processTags(TagMap unsafeTags, DDSpanContext spanContext, WritableSpanLinks spanLinks) {
          if (unsafeTags.containsKey("test")) {
            unsafeTags.put("found", "true")
          }
        }
      }

    def chain = new PostProcessorChain(processor1, processor2)
    
    def links = []
    def tags = TagMap.fromMap(["test": "test"])

    when:
    chain.processTags(tags, null, {link -> links.add(link)})

    then:
    assert tags == ["my": "tag"]
    assert links == []
  }
}
