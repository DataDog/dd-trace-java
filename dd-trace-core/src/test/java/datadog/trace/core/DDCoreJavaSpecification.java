package datadog.trace.core;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer.CoreTracerBuilder;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory;
import datadog.trace.test.util.DDJavaSpecification;
import datadog.trace.util.AgentTaskScheduler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

public abstract class DDCoreJavaSpecification extends DDJavaSpecification {

  protected static List<CoreTracer> unclosedTracers = new ArrayList<>();

  protected static class AutoCloseableCoreTracerBuilder extends CoreTracerBuilder {
    @Override
    public CoreTracer build() {
      CoreTracer tracer = super.build();
      unclosedTracers.add(tracer);
      return tracer;
    }
  }

  protected boolean useNoopStatsDClient() {
    return true;
  }

  protected boolean useStrictTraceWrites() {
    return true;
  }

  @BeforeAll
  static void beforeAll() {
    TagsPostProcessorFactory.withAddInternalTags(false);
    TagsPostProcessorFactory.withAddRemoteHostname(false);
  }

  @AfterAll
  static void afterAll() {
    TagsPostProcessorFactory.reset();
  }

  @AfterEach
  void cleanupCore() {
    for (CoreTracer tracer : unclosedTracers) {
      try {
        tracer.close();
      } catch (Throwable ignored) {
      }
    }
    unclosedTracers.clear();
    AgentTaskScheduler.shutdownAndReset(10, TimeUnit.SECONDS);
  }

  protected CoreTracerBuilder tracerBuilder() {
    CoreTracerBuilder builder = new AutoCloseableCoreTracerBuilder();
    if (useNoopStatsDClient()) {
      builder = builder.statsDClient(StatsDClient.NO_OP);
    }
    return builder.strictTraceWrites(useStrictTraceWrites());
  }

  protected DDSpan buildSpan(long timestamp, CharSequence spanType, Map<String, Object> tags) {
    return buildSpan(
        timestamp,
        spanType,
        PropagationTags.factory().empty(),
        tags,
        PrioritySampling.SAMPLER_KEEP,
        null);
  }

  protected DDSpan buildSpan(
      long timestamp, String tag, String value, PropagationTags propagationTags) {
    Map<String, Object> tags = new HashMap<>();
    tags.put(tag, value);
    return buildSpan(timestamp, "fakeType", propagationTags, tags, PrioritySampling.UNSET, null);
  }

  protected DDSpan buildSpan(
      long timestamp,
      CharSequence spanType,
      PropagationTags propagationTags,
      Map<String, Object> tags,
      byte prioritySampling,
      Object ciVisibilityContextData) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1L,
            DDSpanId.ZERO,
            null,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            prioritySampling,
            null,
            Collections.emptyMap(),
            null,
            false,
            spanType,
            0,
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            ciVisibilityContextData,
            NoopPathwayContext.INSTANCE,
            false,
            propagationTags,
            ProfilingContextIntegration.NoOp.INSTANCE,
            true);

    DDSpan span = DDSpan.create("test", timestamp, context, null);
    for (Map.Entry<String, Object> entry : tags.entrySet()) {
      span.setTag(entry.getKey(), entry.getValue());
    }

    tracer.close();
    return span;
  }
}
