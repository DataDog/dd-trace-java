package datadog.trace.core.propagation

import datadog.context.Context
import datadog.context.propagation.CarrierSetter
import datadog.context.propagation.Propagators
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.common.writer.LoggingWriter
import datadog.trace.core.ControllableSampler
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.ProductTraceSource.ASM
import static datadog.trace.api.ProductTraceSource.UNSET
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.XRAY_TRACING_CONCERN
import static datadog.trace.bootstrap.instrumentation.api.Tags.PROPAGATED_TRACE_SOURCE
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY

class TracingPropagatorTest extends DDCoreSpecification {
  HttpCodec.Injector injector
  HttpCodec.Extractor extractor
  TracingPropagator propagator

  def setup() {
    this.injector = Mock(HttpCodec.Injector)
    this.extractor = Mock(HttpCodec.Extractor)
    this.propagator = new TracingPropagator(true, injector, extractor)
  }

  def 'test tracing propagator context injection'() {
    setup:
    def tracer = tracerBuilder().build()
    def span = tracer.buildSpan('test', 'operation').start()
    def setter = Mock(CarrierSetter)
    def carrier = new Object()

    when:
    this.propagator.inject(span, carrier, setter)

    then:
    1 * this.injector.inject(span.context(), carrier, _)

    cleanup:
    span.finish()
    tracer.close()
  }

  def 'test tracing propagator context extractor'() {
    setup:
    def context = Context.root()
    // TODO Use ContextVisitor mock as getter once extractor API is refactored
    def getter = Mock(AgentPropagation.ContextVisitor)
    def carrier = new Object()

    when:
    this.propagator.extract(context, carrier, getter)

    then:
    1 * this.extractor.extract(carrier, _)
  }

  def 'span priority set when injecting'() {
    given:
    injectSysConfig('writer.type', 'LoggingWriter')
    def tracer = tracerBuilder().build()
    def setter = Mock(CarrierSetter)
    def carrier = new Object()

    when:
    def root = tracer.buildSpan('test', 'parent').start()
    def child = tracer.buildSpan('test', 'child').asChildOf(root).start()
    Propagators.defaultPropagator().inject(child, carrier, setter)

    then:
    root.getSamplingPriority() == SAMPLER_KEEP as int
    child.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, SAMPLING_PRIORITY_KEY, String.valueOf(SAMPLER_KEEP))

    cleanup:
    child.finish()
    root.finish()
    tracer.close()
  }

  def 'span priority only set after first injection'() {
    given:
    def sampler = new ControllableSampler()
    def tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build()
    def setter = Mock(CarrierSetter)
    def carrier = new Object()

    when:
    def root = tracer.buildSpan('test', 'parent').start()
    def child = tracer.buildSpan('test', 'child').asChildOf(root).start()
    Propagators.defaultPropagator().inject(child, carrier, setter)

    then:
    root.getSamplingPriority() == SAMPLER_KEEP as int
    child.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, SAMPLING_PRIORITY_KEY, String.valueOf(SAMPLER_KEEP))

    when:
    sampler.nextSamplingPriority = PrioritySampling.SAMPLER_DROP as int
    def child2 = tracer.buildSpan('test', 'child2').asChildOf(root).start()
    Propagators.defaultPropagator().inject(child2, carrier, setter)

    then:
    root.getSamplingPriority() == SAMPLER_KEEP as int
    child.getSamplingPriority() == root.getSamplingPriority()
    child2.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, SAMPLING_PRIORITY_KEY, String.valueOf(SAMPLER_KEEP))

    cleanup:
    child.finish()
    child2.finish()
    root.finish()
    tracer.close()
  }

  def 'injection does not override set priority'() {
    given:
    def sampler = new ControllableSampler()
    def tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build()
    def setter = Mock(CarrierSetter)
    def carrier = new Object()

    when:
    def root = tracer.buildSpan('test', 'root').start()
    def child = tracer.buildSpan('test', 'child').asChildOf(root).start()
    child.setSamplingPriority(USER_DROP)
    Propagators.defaultPropagator().inject(child, carrier, setter)

    then:
    root.getSamplingPriority() == USER_DROP as int
    child.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, SAMPLING_PRIORITY_KEY, String.valueOf(USER_DROP))

    cleanup:
    child.finish()
    root.finish()
    tracer.close()
  }

  def 'test propagation when tracing is disabled'() {
    setup:
    this.propagator = new TracingPropagator(tracingEnabled, injector, extractor)
    def tracer = tracerBuilder().build()
    def span = tracer.buildSpan('test', 'operation').start()
    span.setTag(PROPAGATED_TRACE_SOURCE, product)
    def setter = Mock(CarrierSetter)
    def carrier = new Object()

    when:
    this.propagator.inject(span, carrier, setter)

    then:
    injected * this.injector.inject(span.context(), carrier, _)

    cleanup:
    span.finish()
    tracer.close()

    where:
    tracingEnabled | product
    true           | ASM
    true           | UNSET
    false          | ASM
    false          | UNSET
    injected = tracingEnabled || product != UNSET ? 1 : 0
  }

  def 'test AWS X-Ray propagator'() {
    setup:
    def tracer = tracerBuilder().build()
    def span = tracer.buildSpan('test', 'operation').start()
    def propagator = Propagators.forConcerns(XRAY_TRACING_CONCERN)
    def setter = Mock(CarrierSetter)
    def carrier = new Object()

    when:
    propagator.inject(span, carrier, setter)

    then:
    1 * setter.set(carrier, 'X-Amzn-Trace-Id', _)

    cleanup:
    span.finish()
    tracer.close()
  }

  def 'test APM Tracing disabled propagator stop propagation'() {
    setup:
    injectSysConfig('apm.tracing.enabled', apmTracingEnabled.toString())
    def tracer = tracerBuilder().build()
    def span = tracer.buildSpan('test', 'operation').start()
    def setter = Mock(CarrierSetter)
    def carrier = new Object()

    when:
    Propagators.defaultPropagator().inject(span, carrier, setter)

    then:
    if (apmTracingEnabled) {
      (1.._) * setter.set(_, _, _)
    } else {
      0 * setter.set(_, _, _)
    }

    cleanup:
    span.finish()
    tracer.close()

    where:
    apmTracingEnabled << [true, false]
  }
}
