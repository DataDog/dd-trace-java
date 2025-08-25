package datadog.trace.core.test

import static java.util.Collections.emptyList

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.CoreTracer.CoreTracerBuilder
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory
import datadog.trace.test.util.DDSpecification

abstract class DDCoreSpecification extends DDSpecification {

  protected boolean useNoopStatsDClient() {
    return true
  }

  protected boolean useStrictTraceWrites() {
    return true
  }

  @Override
  void setupSpec() {
    TagsPostProcessorFactory.withAddRemoteHostname(false)
  }

  @Override
  void cleanupSpec() {
    TagsPostProcessorFactory.reset()
  }

  protected CoreTracerBuilder tracerBuilder() {
    def builder = CoreTracer.builder()
    if (useNoopStatsDClient()) {
      builder = builder.statsDClient(StatsDClient.NO_OP)
    }
    return builder
      .strictTraceWrites(useStrictTraceWrites())
      .preWriteTagsPostProcessors(emptyList())
  }

  protected DDSpan buildSpan(long timestamp, CharSequence spanType, Map<String, Object> tags) {
    return buildSpan(timestamp, spanType, PropagationTags.factory().empty(), tags, PrioritySampling.SAMPLER_KEEP, null)
  }

  protected DDSpan buildSpan(long timestamp, String tag, String value, PropagationTags propagationTags) {
    return buildSpan(timestamp, "fakeType", propagationTags, [(tag): value], PrioritySampling.UNSET, null)
  }

  protected DDSpan buildSpan(long timestamp,
    CharSequence spanType,
    PropagationTags propagationTags,
    Map<String, Object> tags,
    byte prioritySampling,
    Object ciVisibilityContextData) {
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    def context = new DDSpanContext(
      DDTraceId.ONE,
      1,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      prioritySampling,
      null,
      [:],
      null,
      false,
      spanType,
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      ciVisibilityContextData,
      NoopPathwayContext.INSTANCE,
      false,
      propagationTags,
      ProfilingContextIntegration.NoOp.INSTANCE,
      true)

    def span = DDSpan.create("test", timestamp, context, null)
    for (Map.Entry<String, Object> e : tags.entrySet()) {
      span.setTag(e.key, e.value)
    }

    tracer.close()
    return span
  }
}
