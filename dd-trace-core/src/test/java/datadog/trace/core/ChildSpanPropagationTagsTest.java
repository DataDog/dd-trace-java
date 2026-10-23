package datadog.trace.core;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.CoreSpan.OWN_PROPAGATION_TAGS;
import static datadog.trace.core.CoreSpan.ROOT_PROPAGATION_TAGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Local child spans don't hold their own {@link PropagationTags} unless something writes to them,
 * yet serialize exactly what an eagerly created empty instance would.
 */
class ChildSpanPropagationTagsTest extends DDCoreJavaSpecification {

  private static final String TRACE_ID_128 = "640cfd8d00000000abcdef0123456789";

  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void cleanup() {
    tracer.close();
  }

  @Test
  void childOfLocalRootHoldsNoPropagationTags() {
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
    DDSpan child = childOf(root);

    child.setSamplingPriority(USER_KEEP, MANUAL);
    child.spanContext().setOrigin("synthetics");
    child.spanContext().getSamplingPriority();
    child.spanContext().getPropagationTags();
    child.processTagsAndBaggage(MetadataConsumer.NO_OP, ROOT_PROPAGATION_TAGS);

    assertFalse(child.spanContext().hasOwnPropagationTags());
    assertEquals(USER_KEEP, root.spanContext().getSamplingPriority());
    assertSame(root.spanContext().getPropagationTags(), child.spanContext().getPropagationTags());
  }

  @Test
  void childSerializesLikeEmptyPropagationTags() {
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
    DDSpan child = childOf(root);

    assertEquals(expectedOwnTags(child), ownTags(child));
    assertFalse(child.spanContext().hasOwnPropagationTags());
  }

  @Test
  void childOf128BitTraceSerializesTraceIdHighOrderBits() {
    DDSpan root = extractedRoot(DDTraceId.fromHex(TRACE_ID_128), "_dd.p.usr=123");
    DDSpan child = childOf(root);

    Map<String, String> own = ownTags(child);

    assertEquals(expectedOwnTags(child), own);
    assertEquals(Collections.singletonMap("_dd.p.tid", "640cfd8d00000000"), own);
    assertFalse(child.spanContext().hasOwnPropagationTags());
  }

  @Test
  void rootSerializationUnaffectedByChildren() {
    DDSpan root = extractedRoot(DDTraceId.fromHex(TRACE_ID_128), "_dd.p.usr=123");
    childOf(root).setSamplingPriority(USER_KEEP, MANUAL);

    Map<String, String> rootTags = tags(root, ROOT_PROPAGATION_TAGS);

    assertEquals(root.spanContext().getPropagationTags().createTagMap(), rootTags);
    assertEquals("123", rootTags.get("_dd.p.usr"));
    assertEquals("640cfd8d00000000", rootTags.get("_dd.p.tid"));
  }

  @Test
  void traceSourceOnChildStaysOnChild() {
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
    DDSpan child = childOf(root);

    child.setTag("_dd.p.ts", ProductTraceSource.ASM);

    assertTrue(child.spanContext().hasOwnPropagationTags());
    assertEquals("02", ownTags(child).get("_dd.p.ts"));
    assertFalse(root.spanContext().getPropagationTags().createTagMap().containsKey("_dd.p.ts"));
  }

  @Test
  void debugPropagationOnChildStaysOnChild() {
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
    DDSpan child = childOf(root);

    child.setTag("_dd.p.debug", "probe");

    assertTrue(child.spanContext().hasOwnPropagationTags());
    assertEquals("probe", ownTags(child).get("_dd.p.debug"));
    assertFalse(root.spanContext().getPropagationTags().createTagMap().containsKey("_dd.p.debug"));
  }

  private DDSpan extractedRoot(DDTraceId traceId, String header) {
    PropagationTags propagationTags =
        tracer
            .getPropagationTagsFactory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, header);
    ExtractedContext extracted =
        new ExtractedContext(traceId, 456, SAMPLER_KEEP, null, propagationTags, DATADOG);
    return (DDSpan) tracer.buildSpan("test", "root").asChildOf(extracted).start();
  }

  private DDSpan childOf(DDSpan parent) {
    return (DDSpan) tracer.buildSpan("test", "child").asChildOf(parent.spanContext()).start();
  }

  /** What a child span's eagerly created empty propagation tags serialized to. */
  private Map<String, String> expectedOwnTags(DDSpan span) {
    PropagationTags empty = tracer.getPropagationTagsFactory().empty();
    empty.updateTraceIdHighOrderBits(span.getTraceId().toHighOrderLong());
    return empty.createTagMap();
  }

  private static Map<String, String> ownTags(DDSpan span) {
    return tags(span, OWN_PROPAGATION_TAGS);
  }

  private static Map<String, String> tags(DDSpan span, int options) {
    Map<String, String> captured = new HashMap<>();
    span.processTagsAndBaggage(metadata -> captured.putAll(metadata.getBaggage()), options);
    return captured;
  }
}
