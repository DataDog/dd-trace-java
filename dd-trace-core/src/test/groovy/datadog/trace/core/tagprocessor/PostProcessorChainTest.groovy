package datadog.trace.core.tagprocessor

import datadog.trace.api.TagMap
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

class PostProcessorChainTest extends DDSpecification {
  def "chain works"() {
    setup:
    def processor1 = new TagsPostProcessor() {
        @Override
        void processTags(TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
          unsafeTags.put("key1", "processor1")
          unsafeTags.put("key2", "processor1")
        }
      }
    def processor2 = new TagsPostProcessor() {
        @Override
        void processTags(TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
          unsafeTags.put("key1", "processor2")
        }
      }

    def chain = new PostProcessorChain(processor1, processor2)
    def tags = TagMap.fromMap(["key1": "root", "key3": "root"])

    when:
    chain.processTags(tags, null, [])

    then:
    assert tags == ["key1": "processor2", "key2": "processor1", "key3": "root"]
  }

  def "processor can hide tags to next one()"() {
    setup:
    def processor1 = new TagsPostProcessor() {
        @Override
        void processTags(TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
          unsafeTags.clear()
          unsafeTags.put("my", "tag")
        }
      }
    def processor2 = new TagsPostProcessor() {
        @Override
        void processTags(TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
          if (unsafeTags.containsKey("test")) {
            unsafeTags.put("found", "true")
          }
        }
      }

    def chain = new PostProcessorChain(processor1, processor2)
    def tags = TagMap.fromMap(["test": "test"])

    when:
    chain.processTags(tags, null, [])

    then:
    assert tags == ["my": "tag"]
  }
}
