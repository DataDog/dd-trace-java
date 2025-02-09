package datadog.trace.core.baggage

import datadog.context.Context
import datadog.context.propagation.CarrierSetter
import datadog.context.propagation.Propagators
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.common.writer.LoggingWriter
import datadog.trace.core.ControllableSampler
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.core.baggage.BaggagePropagator.BAGGAGE_KEY

class BaggagePropagatorTest extends DDCoreSpecification {
  BaggagePropagator propagator

  def setup() {
    this.propagator = new BaggagePropagator()
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
    1 * injector.inject(span.context(), carrier, _)

    cleanup:
    span.finish()
    tracer.close()
  }

  def 'test tracing propagator context extractor'() {
    setup:
    def context = Context.root()
    // TODO Use ContextVisitor mock as getter once extractor API is refactored
    def getter = Mock(AgentPropagation.ContextVisitor)
    def headers = [
      (BAGGAGE_KEY) : baggageHeader,
    ]
    //    BaggagePropagator.BaggageContextExtractor baggageContextExtractor = new BaggagePropagator.BaggageContextExtractor()


    when:
    this.propagator.extract(context, headers, getter)
    //    getter.forEachKeyValue(headers, baggageContextExtractor)

    then:
    1*_
    1*_
    1*_
    1*_
    1*_

    where:
    baggageHeader                                                      | baggageMap
    "key1=val1,key2=val2,foo=bar,x=y"                                  | ["key1": "val1", "key2": "val2", "foo": "bar", "x": "y"]
    "%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C" | ['",;\\()/:<=>?@[]{}': '",;\\']

    //    1 * extractor.extract(carrier, _)
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
    1 * setter.set(carrier, DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(SAMPLER_KEEP))

    cleanup:
    child.finish()
    root.finish()
    tracer.close()
  }

  def 'span priority only set after first injection'() {
    given:
    def sampler = new ControllableSampler()
    def tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build()
    def setter = Mock(AgentPropagation.Setter)
    def carrier = new Object()

    when:
    def root = tracer.buildSpan('test', 'parent').start()
    def child = tracer.buildSpan('test', 'child').asChildOf(root).start()
    Propagators.defaultPropagator().inject(child, carrier, setter)

    then:
    root.getSamplingPriority() == SAMPLER_KEEP as int
    child.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(SAMPLER_KEEP))

    when:
    sampler.nextSamplingPriority = PrioritySampling.SAMPLER_DROP as int
    def child2 = tracer.buildSpan('test', 'child2').asChildOf(root).start()
    Propagators.defaultPropagator().inject(child2, carrier, setter)

    then:
    root.getSamplingPriority() == SAMPLER_KEEP as int
    child.getSamplingPriority() == root.getSamplingPriority()
    child2.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(SAMPLER_KEEP))

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
    def setter = Mock(AgentPropagation.Setter)
    def carrier = new Object()

    when:
    def root = tracer.buildSpan('test', 'root').start()
    def child = tracer.buildSpan('test', 'child').asChildOf(root).start()
    child.setSamplingPriority(USER_DROP)
    Propagators.defaultPropagator().inject(child, carrier, setter)

    then:
    root.getSamplingPriority() == USER_DROP as int
    child.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(USER_DROP))

    cleanup:
    child.finish()
    root.finish()
    tracer.close()
  }
}
