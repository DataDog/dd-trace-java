package datadog.trace.core.propagation;

import static datadog.trace.api.ProductTraceSource.ASM;
import static datadog.trace.api.ProductTraceSource.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.XRAY_TRACING_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PROPAGATED_TRACE_SOURCE;
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.Propagators;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.ControllableSampler;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TracingPropagatorTest extends DDCoreSpecification {

  HttpCodec.Injector injector;
  HttpCodec.Extractor extractor;
  TracingPropagator propagator;

  @BeforeEach
  void setup() {
    injector = mock(HttpCodec.Injector.class);
    extractor = mock(HttpCodec.Extractor.class);
    propagator = new TracingPropagator(true, injector, extractor);
  }

  @Test
  void testTracingPropagatorContextInjection() throws Exception {
    CoreTracer tracer = tracerBuilder().build();
    try {
      AgentSpan span = tracer.buildSpan("test", "operation").start();
      CarrierSetter setter = mock(CarrierSetter.class);
      Object carrier = new Object();

      propagator.inject(span, carrier, setter);

      verify(injector, times(1)).inject(eq((DDSpanContext) span.context()), eq(carrier), any());

      span.finish();
    } finally {
      tracer.close();
    }
  }

  @Test
  void testTracingPropagatorContextExtractor() {
    Context context = Context.root();
    AgentPropagation.ContextVisitor getter = mock(AgentPropagation.ContextVisitor.class);
    Object carrier = new Object();

    propagator.extract(context, carrier, getter);

    verify(extractor, times(1)).extract(eq(carrier), any());
  }

  @Test
  void spanPrioritySetWhenInjecting() throws Exception {
    injectSysConfig("writer.type", "LoggingWriter");
    CoreTracer tracer = tracerBuilder().build();
    try {
      CarrierSetter setter = mock(CarrierSetter.class);
      Object carrier = new Object();

      AgentSpan root = tracer.buildSpan("test", "parent").start();
      AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root).start();
      Propagators.defaultPropagator().inject(child, carrier, setter);

      assertEquals((int) SAMPLER_KEEP, root.getSamplingPriority());
      assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
      verify(setter, times(1)).set(carrier, SAMPLING_PRIORITY_KEY, String.valueOf(SAMPLER_KEEP));

      child.finish();
      root.finish();
    } finally {
      tracer.close();
    }
  }

  @Test
  void spanPriorityOnlySetAfterFirstInjection() throws Exception {
    ControllableSampler sampler = new ControllableSampler();
    CoreTracer tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    try {
      CarrierSetter setter = mock(CarrierSetter.class);
      Object carrier = new Object();

      AgentSpan root = tracer.buildSpan("test", "parent").start();
      AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root).start();
      Propagators.defaultPropagator().inject(child, carrier, setter);

      assertEquals((int) SAMPLER_KEEP, root.getSamplingPriority());
      assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
      verify(setter, times(1)).set(carrier, SAMPLING_PRIORITY_KEY, String.valueOf(SAMPLER_KEEP));

      sampler.nextSamplingPriority = (int) PrioritySampling.SAMPLER_DROP;
      AgentSpan child2 = tracer.buildSpan("test", "child2").asChildOf(root).start();
      Propagators.defaultPropagator().inject(child2, carrier, setter);

      assertEquals((int) SAMPLER_KEEP, root.getSamplingPriority());
      assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
      assertEquals(root.getSamplingPriority(), child2.getSamplingPriority());
      verify(setter, times(2)).set(carrier, SAMPLING_PRIORITY_KEY, String.valueOf(SAMPLER_KEEP));

      child.finish();
      child2.finish();
      root.finish();
    } finally {
      tracer.close();
    }
  }

  @Test
  void injectionDoesNotOverrideSetPriority() throws Exception {
    ControllableSampler sampler = new ControllableSampler();
    CoreTracer tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    try {
      CarrierSetter setter = mock(CarrierSetter.class);
      Object carrier = new Object();

      AgentSpan root = tracer.buildSpan("test", "root").start();
      AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root).start();
      child.setSamplingPriority(USER_DROP);
      Propagators.defaultPropagator().inject(child, carrier, setter);

      assertEquals((int) USER_DROP, root.getSamplingPriority());
      assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
      verify(setter, times(1)).set(carrier, SAMPLING_PRIORITY_KEY, String.valueOf(USER_DROP));

      child.finish();
      root.finish();
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> testPropagationWhenTracingIsDisabledArguments() {
    return Stream.of(
        Arguments.of(true, ASM),
        Arguments.of(true, UNSET),
        Arguments.of(false, ASM),
        Arguments.of(false, UNSET));
  }

  @ParameterizedTest
  @MethodSource("testPropagationWhenTracingIsDisabledArguments")
  void testPropagationWhenTracingIsDisabled(boolean tracingEnabled, int product) throws Exception {
    this.propagator = new TracingPropagator(tracingEnabled, injector, extractor);
    CoreTracer tracer = tracerBuilder().build();
    try {
      AgentSpan span = tracer.buildSpan("test", "operation").start();
      span.setTag(PROPAGATED_TRACE_SOURCE, product);
      CarrierSetter setter = mock(CarrierSetter.class);
      Object carrier = new Object();

      propagator.inject(span, carrier, setter);

      int injected = (tracingEnabled || product != UNSET) ? 1 : 0;
      verify(injector, times(injected))
          .inject(eq((DDSpanContext) span.context()), eq(carrier), any());

      span.finish();
    } finally {
      tracer.close();
    }
  }

  @Test
  void testAwsXRayPropagator() throws Exception {
    CoreTracer tracer = tracerBuilder().build();
    try {
      AgentSpan span = tracer.buildSpan("test", "operation").start();
      datadog.context.propagation.Propagator xrayPropagator =
          Propagators.forConcerns(XRAY_TRACING_CONCERN);
      CarrierSetter setter = mock(CarrierSetter.class);
      Object carrier = new Object();

      xrayPropagator.inject(span, carrier, setter);

      verify(setter, times(1)).set(eq(carrier), eq("X-Amzn-Trace-Id"), any());

      span.finish();
    } finally {
      tracer.close();
    }
  }

  @ParameterizedTest
  @MethodSource("testApmTracingDisabledPropagatorArguments")
  void testApmTracingDisabledPropagatorStopPropagation(boolean apmTracingEnabled) throws Exception {
    injectSysConfig("apm.tracing.enabled", String.valueOf(apmTracingEnabled));
    CoreTracer tracer = tracerBuilder().build();
    try {
      AgentSpan span = tracer.buildSpan("test", "operation").start();
      CarrierSetter setter = mock(CarrierSetter.class);
      Object carrier = new Object();

      Propagators.defaultPropagator().inject(span, carrier, setter);

      if (apmTracingEnabled) {
        verify(setter, atLeastOnce()).set(any(), any(), any());
      } else {
        verify(setter, never()).set(any(), any(), any());
      }

      span.finish();
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> testApmTracingDisabledPropagatorArguments() {
    return Stream.of(Arguments.of(true), Arguments.of(false));
  }
}
