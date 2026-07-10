package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tabletest.junit.TableTest;

class SpanPointersProcessorTest extends DDJavaSpecification {

  @TableTest({
    "scenario       | objectKey     | eTag       | expectedHash                    ",
    "basic values   | some-key.data | ab12ef34   | e721375466d4116ab551213fdea08413",
    "non-ascii key  | some-key.你好 | ab12ef34   | d1333a04b9928ab462b5c6cadfa401f4  ",
    "multipart etag | some-key.data | ab12ef34-5 | 2b90dffc37ebc7bc610152c3dc72af9f"
  })
  void spanPointersProcessorAddsCorrectLink(String objectKey, String eTag, String expectedHash) {
    SpanPointersProcessor processor = new SpanPointersProcessor();

    Map<String, Object> tagMap = new LinkedHashMap<>();
    tagMap.put(InstrumentationTags.AWS_BUCKET_NAME, "some-bucket");
    tagMap.put(InstrumentationTags.AWS_OBJECT_KEY, objectKey);
    tagMap.put("s3.eTag", eTag);

    TagMap unsafeTags = TagMap.fromMap(tagMap);
    DDSpanContext spanContext = mock(DDSpanContext.class);
    List<AgentSpanLink> spanLinks = new ArrayList<>();

    // Process the tags; the processor should remove 's3.eTag' and add one link
    processor.processTags(unsafeTags, spanContext, link -> spanLinks.add(link));

    // 1. s3.eTag was removed
    assertFalse(unsafeTags.containsKey("s3.eTag"));
    // 2. Exactly one link was added
    assertEquals(1, spanLinks.size());
    // 3. Check link
    AgentSpanLink link = spanLinks.get(0);
    assertInstanceOf(SpanLink.class, link);
    assertEquals(DDTraceId.ZERO, link.traceId());
    assertEquals(DDSpanId.ZERO, link.spanId());
    assertEquals(SpanPointersProcessor.S3_PTR_KIND, link.attributes().asMap().get("ptr.kind"));
    assertEquals(SpanPointersProcessor.DOWN_DIRECTION, link.attributes().asMap().get("ptr.dir"));
    assertEquals(expectedHash, link.attributes().asMap().get("ptr.hash"));
    assertEquals(SpanPointersProcessor.LINK_KIND, link.attributes().asMap().get("link.kind"));
  }
}
