package datadog.trace.core.propagation;

import static datadog.trace.api.ProductTraceSource.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.XRAY_TRACING_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PROPAGATED_TRACE_SOURCE;
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.XRayHttpCodec.X_AMZN_TRACE_ID;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.context.Context;
import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.junit.utils.converter.ProductTraceSourceConverter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class TracingPropagatorTest extends DDCoreJavaSpecification {

  private HttpCodec.Injector injector;
  private HttpCodec.Extractor extractor;
  private TracingPropagator propagator;

  @BeforeEach
  void setup() {
    this.injector = mock(HttpCodec.Injector.class);
    this.extractor = mock(HttpCodec.Extractor.class);
    this.propagator = new TracingPropagator(true, this.injector, this.extractor);
  }

  @Test
  void testTracingPropagatorContextInjection() {
    CoreTracer tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "operation").start();
    Map<String, String> carrier = new HashMap<>();

    this.propagator.inject(span, carrier, Map::put);

    verify(this.injector).inject(same((DDSpanContext) span.spanContext()), same(carrier), any());

    span.finish();
    tracer.close();
  }

  @Test
  void testTracingPropagatorContextExtractor() {
    Context context = Context.root();
    Map<String, String> carrier = new HashMap<>();

    this.propagator.extract(context, carrier, stringValuesMap());

    verify(this.extractor).extract(same(carrier), any());
  }

  @Test
  void spanPrioritySetWhenInjecting() {
    CoreTracer tracer = tracerBuilder().build();
    Map<String, String> carrier = new HashMap<>();

    AgentSpan root = tracer.buildSpan("test", "parent").start();
    AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root).start();
    Propagators.defaultPropagator().inject(child, carrier, Map::put);

    assertEquals(SAMPLER_KEEP, root.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
    assertEquals(String.valueOf(SAMPLER_KEEP), carrier.get(SAMPLING_PRIORITY_KEY));

    child.finish();
    root.finish();
    tracer.close();
  }

  @Test
  void spanPriorityOnlySetAfterFirstInjection() {
    ControllableSampler sampler = new ControllableSampler();
    CoreTracer tracer = tracerBuilder().sampler(sampler).build();

    AgentSpan root = tracer.buildSpan("test", "parent").start();
    AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root).start();

    Map<String, String> carrier = new HashMap<>();
    Propagators.defaultPropagator().inject(child, carrier, Map::put);

    assertEquals(SAMPLER_KEEP, root.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
    assertEquals(String.valueOf(SAMPLER_KEEP), carrier.get(SAMPLING_PRIORITY_KEY));

    sampler.nextSamplingPriority = SAMPLER_DROP;
    AgentSpan child2 = tracer.buildSpan("test", "child2").asChildOf(root).start();
    Propagators.defaultPropagator().inject(child2, carrier, Map::put);

    assertEquals(SAMPLER_KEEP, root.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child2.getSamplingPriority());
    assertEquals(String.valueOf(SAMPLER_KEEP), carrier.get(SAMPLING_PRIORITY_KEY));

    child.finish();
    child2.finish();
    root.finish();
    tracer.close();
  }

  @Test
  void injectionDoesNotOverrideSetPriority() {
    ControllableSampler sampler = new ControllableSampler();
    CoreTracer tracer = tracerBuilder().sampler(sampler).build();

    AgentSpan root = tracer.buildSpan("test", "root").start();
    AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root).start();
    child.setSamplingPriority(USER_DROP);

    Map<String, String> carrier = new HashMap<>();
    Propagators.defaultPropagator().inject(child, carrier, Map::put);

    assertEquals(USER_DROP, root.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
    assertEquals(String.valueOf(USER_DROP), carrier.get(SAMPLING_PRIORITY_KEY));

    child.finish();
    root.finish();
    tracer.close();
  }

  @TableTest({
    "tracingEnabled | product                 ",
    "true           | ProductTraceSource.ASM  ",
    "true           | ProductTraceSource.UNSET",
    "false          | ProductTraceSource.ASM  ",
    "false          | ProductTraceSource.UNSET"
  })
  void testPropagationWhenTracingIsDisabled(
      boolean tracingEnabled, @ConvertWith(ProductTraceSourceConverter.class) int product) {
    // Recreating propagator to apply tracing test flag
    this.propagator = new TracingPropagator(tracingEnabled, this.injector, this.extractor);

    CoreTracer tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "operation").start();
    span.setTag(PROPAGATED_TRACE_SOURCE, product);

    Map<String, String> carrier = new HashMap<>();
    this.propagator.inject(span, carrier, Map::put);

    int injected = (tracingEnabled || product != UNSET) ? 1 : 0;
    verify(this.injector, times(injected))
        .inject(same((DDSpanContext) span.spanContext()), same(carrier), any());

    span.finish();
    tracer.close();
  }

  @Test
  void testAwsXRayPropagator() {
    CoreTracer tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "operation").start();
    Propagator xrayPropagator = Propagators.forConcerns(XRAY_TRACING_CONCERN);

    Map<String, String> carrier = new HashMap<>();
    xrayPropagator.inject(span, carrier, Map::put);

    assertNotNull(carrier.get(X_AMZN_TRACE_ID));

    span.finish();
    tracer.close();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testApmTracingDisabledPropagatorStopPropagation(boolean apmTracingEnabled) {
    injectSysConfig("apm.tracing.enabled", String.valueOf(apmTracingEnabled));
    CoreTracer tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "operation").start();

    Map<String, String> carrier = new HashMap<>();
    Propagators.defaultPropagator().inject(span, carrier, Map::put);

    assertEquals(apmTracingEnabled, !carrier.isEmpty());

    span.finish();
    tracer.close();
  }
}
