package datadog.trace.core.baggage

import datadog.context.Context
import datadog.context.propagation.CarrierSetter
import datadog.trace.bootstrap.instrumentation.api.BaggageContext
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.core.test.DDCoreSpecification
import static datadog.trace.core.baggage.BaggagePropagator.BAGGAGE_KEY

class BaggagePropagatorTest extends DDCoreSpecification {
  BaggagePropagator propagator

  def setup() {
    this.propagator = new BaggagePropagator()
  }

  def 'test tracing propagator context injection'() {
    setup:
    def setter = Mock(CarrierSetter)
    def carrier = new Object()
    def context = Context.root()
    context = BaggageContext.create(baggageMap).storeInto(context)


    when:
    this.propagator.inject(context, carrier, setter)

    then:
    1 * setter.set(carrier, BAGGAGE_KEY, baggageHeader)

    where:
    baggageMap                                               | baggageHeader
    ["key1": "val1", "key2": "val2", "foo": "bar", "x": "y"] | "key1=val1,key2=val2,foo=bar,x=y"
    ['",;\\()/:<=>?@[]{}': '",;\\']                          | "%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C"
  }

  def 'test tracing propagator context extractor'() {
    setup:
    def context = Context.root()
    // TODO Use ContextVisitor mock as getter once extractor API is refactored
    def headers = [
      (BAGGAGE_KEY) : baggageHeader,
    ]

    when:
    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap())

    then:
    BaggageContext.fromContext(context).getBaggage() == baggageMap

    where:
    baggageHeader                                                      | baggageMap
    "key1=val1,key2=val2,foo=bar,x=y"                                  | ["key1": "val1", "key2": "val2", "foo": "bar", "x": "y"]
    "%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C" | ['",;\\()/:<=>?@[]{}': '",;\\']

  }
}
