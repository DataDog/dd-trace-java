package datadog.trace.core.test


import datadog.metrics.statsd.StatsDClient
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.CoreTracer.CoreTracerBuilder
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler
import spock.lang.Shared

import java.util.concurrent.TimeUnit

abstract class DDCoreSpecification extends DDSpecification {
  @Shared
  static List<CoreTracer> unclosedTracers = []


  static class AutoCloseableCoreTracerBuilder extends CoreTracerBuilder {
    @Override
    CoreTracer build() {
      def ret = super.build()
      unclosedTracers.add(ret)
      ret
    }
  }


  protected boolean useNoopStatsDClient() {
    return true
  }

  protected boolean useStrictTraceWrites() {
    return true
  }

  @Override
  void setupSpec() {
    TagsPostProcessorFactory.withAddBaseService(false)
    TagsPostProcessorFactory.withAddRemoteHostname(false)
  }

  @Override
  void cleanupSpec() {
    TagsPostProcessorFactory.reset()
  }

  @Override
  void cleanup() {
    unclosedTracers.each {
      try {
        it.close()
      } catch (Throwable ignored) {
      }
    }
    unclosedTracers.clear()
    AgentTaskScheduler.shutdownAndReset(10, TimeUnit.SECONDS)
  }

  protected CoreTracerBuilder tracerBuilder() {
    def builder = new AutoCloseableCoreTracerBuilder()
    if (useNoopStatsDClient()) {
      builder = builder.statsDClient(StatsDClient.NO_OP)
    }
    builder.strictTraceWrites(useStrictTraceWrites())
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
