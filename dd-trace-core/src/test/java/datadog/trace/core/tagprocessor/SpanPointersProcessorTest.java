package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpanPointersProcessorTest {

  @Mock DDSpanContext spanContext;

  @Test
  void spanPointersProcessorAddsCorrectLinkWithBasicValues() {
    SpanPointersProcessor processor = new SpanPointersProcessor();
    Map<String, Object> unsafeTags = new LinkedHashMap<>();
    unsafeTags.put(InstrumentationTags.AWS_BUCKET_NAME, "some-bucket");
    unsafeTags.put(InstrumentationTags.AWS_OBJECT_KEY, "some-key.data");
    unsafeTags.put("s3.eTag", "ab12ef34");
    List<AgentSpanLink> spanLinks = new ArrayList<>();
    String expectedHash = "e721375466d4116ab551213fdea08413";

    Map<String, Object> returnedTags = processor.processTags(unsafeTags, spanContext, spanLinks);

    assertFalse(returnedTags.containsKey("s3.eTag"));
    assertEquals(1, spanLinks.size());

    AgentSpanLink link = spanLinks.get(0);
    assertTrue(link instanceof SpanLink);
    assertEquals(DDTraceId.ZERO, link.traceId());
    assertEquals(DDSpanId.ZERO, link.spanId());
    assertEquals(SpanPointersProcessor.S3_PTR_KIND, link.attributes().asMap().get("ptr.kind"));
    assertEquals(SpanPointersProcessor.DOWN_DIRECTION, link.attributes().asMap().get("ptr.dir"));
    assertEquals(expectedHash, link.attributes().asMap().get("ptr.hash"));
    assertEquals(SpanPointersProcessor.LINK_KIND, link.attributes().asMap().get("link.kind"));
  }

  @Test
  void spanPointersProcessorAddsCorrectLinkWithNonAsciiKey() {
    SpanPointersProcessor processor = new SpanPointersProcessor();
    Map<String, Object> unsafeTags = new LinkedHashMap<>();
    unsafeTags.put(InstrumentationTags.AWS_BUCKET_NAME, "some-bucket");
    unsafeTags.put(InstrumentationTags.AWS_OBJECT_KEY, "some-key.\u4f60\u597d");
    unsafeTags.put("s3.eTag", "ab12ef34");
    List<AgentSpanLink> spanLinks = new ArrayList<>();
    String expectedHash = "d1333a04b9928ab462b5c6cadfa401f4";

    Map<String, Object> returnedTags = processor.processTags(unsafeTags, spanContext, spanLinks);

    assertFalse(returnedTags.containsKey("s3.eTag"));
    assertEquals(1, spanLinks.size());

    AgentSpanLink link = spanLinks.get(0);
    assertEquals(DDTraceId.ZERO, link.traceId());
    assertEquals(DDSpanId.ZERO, link.spanId());
    assertEquals(SpanPointersProcessor.S3_PTR_KIND, link.attributes().asMap().get("ptr.kind"));
    assertEquals(SpanPointersProcessor.DOWN_DIRECTION, link.attributes().asMap().get("ptr.dir"));
    assertEquals(expectedHash, link.attributes().asMap().get("ptr.hash"));
    assertEquals(SpanPointersProcessor.LINK_KIND, link.attributes().asMap().get("link.kind"));
  }

  @Test
  void spanPointersProcessorAddsCorrectLinkWithMultipartUploadETag() {
    SpanPointersProcessor processor = new SpanPointersProcessor();
    Map<String, Object> unsafeTags = new LinkedHashMap<>();
    unsafeTags.put(InstrumentationTags.AWS_BUCKET_NAME, "some-bucket");
    unsafeTags.put(InstrumentationTags.AWS_OBJECT_KEY, "some-key.data");
    unsafeTags.put("s3.eTag", "ab12ef34-5");
    List<AgentSpanLink> spanLinks = new ArrayList<>();
    String expectedHash = "2b90dffc37ebc7bc610152c3dc72af9f";

    Map<String, Object> returnedTags = processor.processTags(unsafeTags, spanContext, spanLinks);

    assertFalse(returnedTags.containsKey("s3.eTag"));
    assertEquals(1, spanLinks.size());

    AgentSpanLink link = spanLinks.get(0);
    assertEquals(DDTraceId.ZERO, link.traceId());
    assertEquals(DDSpanId.ZERO, link.spanId());
    assertEquals(SpanPointersProcessor.S3_PTR_KIND, link.attributes().asMap().get("ptr.kind"));
    assertEquals(SpanPointersProcessor.DOWN_DIRECTION, link.attributes().asMap().get("ptr.dir"));
    assertEquals(expectedHash, link.attributes().asMap().get("ptr.hash"));
    assertEquals(SpanPointersProcessor.LINK_KIND, link.attributes().asMap().get("link.kind"));
  }
}
