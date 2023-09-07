package datadog.trace.core.propagation

import datadog.trace.api.TracePropagationStyle
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.common.writer.LoggingWriter
import datadog.trace.core.ControllableSampler
import datadog.trace.core.datastreams.DataStreamContextInjector
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.TracePropagationStyle.B3MULTI
import static datadog.trace.api.TracePropagationStyle.DATADOG
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP

class CorePropagationTest extends DDCoreSpecification {
  HttpCodec.Extractor extractor
  HttpCodec.Injector datadogInjector
  HttpCodec.Injector b3Injector
  HttpCodec.Injector traceContextInjector
  Map<TracePropagationStyle, HttpCodec.Injector> allInjectors
  DataStreamContextInjector dataStreamContextInjector
  AgentPropagation propagation

  def setup() {
    extractor = Mock(HttpCodec.Extractor)
    datadogInjector = Mock(HttpCodec.Injector)
    b3Injector = Mock(HttpCodec.Injector)
    traceContextInjector = Mock(HttpCodec.Injector)
    allInjectors = [
      (DATADOG)     : datadogInjector,
      (B3MULTI)     : b3Injector,
      (TRACECONTEXT): traceContextInjector,
    ]
    dataStreamContextInjector = Mock(DataStreamContextInjector)
    propagation = new CorePropagation(extractor, datadogInjector, allInjectors, dataStreamContextInjector)
  }

  def 'test default injector for span'() {
    setup:
    def tracer = tracerBuilder().build()
    def span = tracer.buildSpan('test', 'operation').start()
    def setter = Mock(AgentPropagation.Setter)
    def carrier = new Object()

    when:
    propagation.inject(span, carrier, setter)

    then:
    1 * datadogInjector.inject(_, carrier, setter)
    0 * b3Injector.inject(_, carrier, setter)
    0 * traceContextInjector.inject(_, carrier, setter)
    0 * dataStreamContextInjector.injectPathwayContext(_, carrier, setter, _)

    cleanup:
    span.finish()
    tracer.close()
  }

  def 'test default injector for span context'() {
    setup:
    def tracer = tracerBuilder().build()
    def span = tracer.buildSpan("test", "operation").start()
    def setter = Mock(AgentPropagation.Setter)
    def carrier = new Object()

    when:
    def spanContext = span.context()
    propagation.inject(spanContext, carrier, setter)

    then:
    1 * datadogInjector.inject(_, carrier, setter)
    0 * b3Injector.inject(_, carrier, setter)
    0 * traceContextInjector.inject(_, carrier, setter)
    0 * dataStreamContextInjector.injectPathwayContext(_, carrier, setter, _)

    cleanup:
    span.finish()
    tracer.close()
  }

  def 'test injector style selection'() {
    setup:
    def injector = allInjectors.get(style, datadogInjector)
    def tracer = tracerBuilder().build()
    def span = tracer.buildSpan('test', 'operation').start()
    def setter = Mock(AgentPropagation.Setter)
    def carrier = new Object()

    when:
    propagation.inject(span, carrier, setter, style)

    then:
    1 * injector.inject(_, carrier, setter)
    if (injector != datadogInjector) {
      0 * datadogInjector.inject(_, carrier, setter)
    }
    if (injector != b3Injector) {
      0 * b3Injector.inject(_, carrier, setter)
    }
    if (injector != traceContextInjector) {
      0 * traceContextInjector.inject(_, carrier, setter)
    }
    0 * dataStreamContextInjector.injectPathwayContext(_, carrier, setter, _)

    cleanup:
    span.finish()
    tracer.close()

    where:
    style << [DATADOG, B3MULTI, TRACECONTEXT, null]
  }

  def 'test context extractor'() {
    setup:
    def getter = Mock(AgentPropagation.ContextVisitor)
    def carrier = new Object()

    when:
    propagation.extract(carrier, getter)

    then:
    1 * extractor.extract(carrier, getter)
  }

  def 'span priority set when injecting'() {
    given:
    injectSysConfig('writer.type', 'LoggingWriter')
    def tracer = tracerBuilder().build()
    def setter = Mock(AgentPropagation.Setter)
    def carrier = new Object()

    when:
    def root = tracer.buildSpan('test', 'operation').start()
    def child = tracer.buildSpan('test', 'my_child').asChildOf(root).start()
    tracer.propagate().inject(child, carrier, setter)

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
    def root = tracer.buildSpan('test', 'operation').start()
    def child = tracer.buildSpan('test', 'my_child').asChildOf(root).start()
    tracer.propagate().inject(child, carrier, setter)

    then:
    root.getSamplingPriority() == SAMPLER_KEEP as int
    child.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(SAMPLER_KEEP))

    when:
    sampler.nextSamplingPriority = PrioritySampling.SAMPLER_DROP as int
    def child2 = tracer.buildSpan('test', 'my_child2').asChildOf(root).start()
    tracer.propagate().inject(child2, carrier, setter)

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

  def "injection doesn't override set priority"() {
    given:
    def sampler = new ControllableSampler()
    def tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build()
    def setter = Mock(AgentPropagation.Setter)
    def carrier = new Object()

    when:
    def root = tracer.buildSpan('test', 'operation').start()
    def child = tracer.buildSpan('test', 'my_child').asChildOf(root).start()
    child.setSamplingPriority(PrioritySampling.USER_DROP)
    tracer.propagate().inject(child, carrier, setter)

    then:
    root.getSamplingPriority() == PrioritySampling.USER_DROP as int
    child.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(PrioritySampling.USER_DROP))

    cleanup:
    child.finish()
    root.finish()
    tracer.close()
  }
}
