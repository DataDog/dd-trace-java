package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.XRAY_TRACING_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PROPAGATED_TRACE_SOURCE;
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.junit.utils.tabletest.ProductTraceSourceConverter;
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
    injector = mock(HttpCodec.Injector.class);
    extractor = mock(HttpCodec.Extractor.class);
    propagator = new TracingPropagator(true, injector, extractor);
  }

  @Test
  void testTracingPropagatorContextInjection() {
    CoreTracer tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "operation").start();
    @SuppressWarnings("unchecked")
    CarrierSetter<Object> setter = mock(CarrierSetter.class);
    Object carrier = new Object();

    propagator.inject(span, carrier, setter);

    verify(injector).inject(same((DDSpanContext) span.context()), same(carrier), any());

    span.finish();
    tracer.close();
  }

  @Test
  void testTracingPropagatorContextExtractor() {
    Context context = Context.root();
    // TODO Use ContextVisitor mock as getter once extractor API is refactored
    @SuppressWarnings("unchecked")
    AgentPropagation.ContextVisitor<Object> getter = mock(AgentPropagation.ContextVisitor.class);
    Object carrier = new Object();

    propagator.extract(context, carrier, getter);

    verify(extractor).extract(same(carrier), any());
  }

  @Test
  @WithConfig(key = TracerConfig.WRITER_TYPE, value = "LoggingWriter")
  void spanPrioritySetWhenInjecting() {
    CoreTracer tracer = tracerBuilder().build();
    @SuppressWarnings("unchecked")
    CarrierSetter<Object> setter = mock(CarrierSetter.class);
    Object carrier = new Object();

    AgentSpan root = tracer.buildSpan("test", "parent").start();
    AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root).start();
    Propagators.defaultPropagator().inject(child, carrier, setter);

    assertEquals(SAMPLER_KEEP, root.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
    verify(setter).set(same(carrier), eq(SAMPLING_PRIORITY_KEY), eq(String.valueOf(SAMPLER_KEEP)));

    child.finish();
    root.finish();
    tracer.close();
  }

  @Test
  void spanPriorityOnlySetAfterFirstInjection() {
    ControllableSampler sampler = new ControllableSampler();
    CoreTracer tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    @SuppressWarnings("unchecked")
    CarrierSetter<Object> setter = mock(CarrierSetter.class);
    Object carrier = new Object();

    AgentSpan root = tracer.buildSpan("test", "parent").start();
    AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root).start();
    Propagators.defaultPropagator().inject(child, carrier, setter);

    assertEquals(SAMPLER_KEEP, root.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
    verify(setter).set(same(carrier), eq(SAMPLING_PRIORITY_KEY), eq(String.valueOf(SAMPLER_KEEP)));
    clearInvocations(setter);

    sampler.nextSamplingPriority = PrioritySampling.SAMPLER_DROP;
    AgentSpan child2 = tracer.buildSpan("test", "child2").asChildOf(root).start();
    Propagators.defaultPropagator().inject(child2, carrier, setter);

    assertEquals(SAMPLER_KEEP, root.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child2.getSamplingPriority());
    verify(setter).set(same(carrier), eq(SAMPLING_PRIORITY_KEY), eq(String.valueOf(SAMPLER_KEEP)));

    child.finish();
    child2.finish();
    root.finish();
    tracer.close();
  }

  @Test
  void injectionDoesNotOverrideSetPriority() {
    ControllableSampler sampler = new ControllableSampler();
    CoreTracer tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    @SuppressWarnings("unchecked")
    CarrierSetter<Object> setter = mock(CarrierSetter.class);
    Object carrier = new Object();

    AgentSpan root = tracer.buildSpan("test", "root").start();
    AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root).start();
    child.setSamplingPriority(USER_DROP);
    Propagators.defaultPropagator().inject(child, carrier, setter);

    assertEquals(USER_DROP, root.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
    verify(setter).set(same(carrier), eq(SAMPLING_PRIORITY_KEY), eq(String.valueOf(USER_DROP)));

    child.finish();
    root.finish();
    tracer.close();
  }

  @TableTest({
    "scenario            | tracingEnabled | product                 ",
    "enabled with ASM    | true           | ProductTraceSource.ASM  ",
    "enabled with UNSET  | true           | ProductTraceSource.UNSET",
    "disabled with ASM   | false          | ProductTraceSource.ASM  ",
    "disabled with UNSET | false          | ProductTraceSource.UNSET"
  })
  void testPropagationWhenTracingIsDisabled(
      boolean tracingEnabled, @ConvertWith(ProductTraceSourceConverter.class) int product) {
    propagator = new TracingPropagator(tracingEnabled, injector, extractor);
    CoreTracer tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "operation").start();
    span.setTag(PROPAGATED_TRACE_SOURCE, product);
    @SuppressWarnings("unchecked")
    CarrierSetter<Object> setter = mock(CarrierSetter.class);
    Object carrier = new Object();
    int injected = (tracingEnabled || product != 0) ? 1 : 0;

    propagator.inject(span, carrier, setter);

    verify(injector, times(injected)).inject(same((DDSpanContext)span.context()), same(carrier), any());

    span.finish();
    tracer.close();
  }

  @Test
  void testAwsXRayPropagator() {
    CoreTracer tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "operation").start();
    Propagator xrayPropagator = Propagators.forConcerns(XRAY_TRACING_CONCERN);
    @SuppressWarnings("unchecked")
    CarrierSetter<Object> setter = mock(CarrierSetter.class);
    Object carrier = new Object();

    xrayPropagator.inject(span, carrier, setter);

    verify(setter).set(same(carrier), eq("X-Amzn-Trace-Id"), any());

    span.finish();
    tracer.close();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testApmTracingDisabledPropagatorStopPropagation(boolean apmTracingEnabled) {
    injectSysConfig("apm.tracing.enabled", String.valueOf(apmTracingEnabled));
    CoreTracer tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "operation").start();
    @SuppressWarnings("unchecked")
    CarrierSetter<Object> setter = mock(CarrierSetter.class);
    Object carrier = new Object();

    Propagators.defaultPropagator().inject(span, carrier, setter);

    if (apmTracingEnabled) {
      verify(setter, atLeastOnce()).set(any(), any(), any());
    } else {
      verifyNoInteractions(setter);
    }

    span.finish();
    tracer.close();
  }
}
