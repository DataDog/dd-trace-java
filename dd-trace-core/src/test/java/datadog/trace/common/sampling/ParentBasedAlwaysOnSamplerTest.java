package datadog.trace.common.sampling;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTraceId;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ParentBasedAlwaysOnSamplerTest {

  private final ListWriter writer = new ListWriter();
  private CoreTracer tracer;

  @AfterEach
  void tearDown() {
    if (tracer != null) {
      tracer.close();
    }
  }

  private CoreTracer buildTracer(ParentBasedAlwaysOnSampler sampler) {
    tracer = CoreTracer.builder().writer(writer).sampler(sampler).build();
    return tracer;
  }

  @Test
  void alwaysSamplesSpans() {
    ParentBasedAlwaysOnSampler sampler = new ParentBasedAlwaysOnSampler();
    CoreTracer tracer = buildTracer(sampler);

    DDSpan span = (DDSpan) tracer.buildSpan("test").start();
    try {
      assertTrue(sampler.sample(span));
    } finally {
      span.finish();
    }
  }

  @Test
  void setsSamplingPriorityToSamplerKeep() {
    ParentBasedAlwaysOnSampler sampler = new ParentBasedAlwaysOnSampler();
    CoreTracer tracer = buildTracer(sampler);

    DDSpan span = (DDSpan) tracer.buildSpan("test").start();
    try {
      sampler.setSamplingPriority(span);
      assertEquals(SAMPLER_KEEP, span.getSamplingPriority());
    } finally {
      span.finish();
    }
  }

  @Test
  void childSpanInheritsSamplingPriorityFromLocalParent() {
    ParentBasedAlwaysOnSampler sampler = new ParentBasedAlwaysOnSampler();
    CoreTracer tracer = buildTracer(sampler);

    DDSpan rootSpan = (DDSpan) tracer.buildSpan("root").start();
    sampler.setSamplingPriority(rootSpan);
    DDSpan childSpan = (DDSpan) tracer.buildSpan("child").asChildOf(rootSpan.context()).start();
    try {
      assertEquals(SAMPLER_KEEP, rootSpan.getSamplingPriority());
      assertEquals(SAMPLER_KEEP, childSpan.getSamplingPriority());
    } finally {
      childSpan.finish();
      rootSpan.finish();
    }
  }

  static Stream<Arguments> childSpanInheritsSamplingDecisionFromRemoteParentArguments() {
    return Stream.of(
        Arguments.arguments("sampler keep", SAMPLER_KEEP),
        Arguments.arguments("sampler drop", SAMPLER_DROP),
        Arguments.arguments("user keep", USER_KEEP),
        Arguments.arguments("user drop", USER_DROP));
  }

  @ParameterizedTest(name = "child span inherits sampling decision from remote parent: {0}")
  @MethodSource("childSpanInheritsSamplingDecisionFromRemoteParentArguments")
  void childSpanInheritsSamplingDecisionFromRemoteParent(String scenario, int parentPriority) {
    ParentBasedAlwaysOnSampler sampler = new ParentBasedAlwaysOnSampler();
    CoreTracer tracer = buildTracer(sampler);

    ExtractedContext extractedContext =
        new ExtractedContext(
            DDTraceId.ONE, 2, parentPriority, null, PropagationTags.factory().empty(), DATADOG);

    DDSpan span = (DDSpan) tracer.buildSpan("child").asChildOf(extractedContext).start();
    try {
      assertEquals(parentPriority, span.getSamplingPriority());
    } finally {
      span.finish();
    }
  }

  @Test
  void isNotARemoteResponseListener() {
    ParentBasedAlwaysOnSampler sampler = new ParentBasedAlwaysOnSampler();
    assertFalse(sampler instanceof RemoteResponseListener);
  }

  @Test
  void implementsPrioritySampler() {
    ParentBasedAlwaysOnSampler sampler = new ParentBasedAlwaysOnSampler();
    assertTrue(sampler instanceof PrioritySampler);
  }
}
