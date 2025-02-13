package datadog.trace.core.baggage

import datadog.context.Context
import datadog.context.EmptyContext
import datadog.context.propagation.CarrierSetter
import datadog.context.propagation.CarrierVisitor
import datadog.trace.bootstrap.instrumentation.api.BaggageContext
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.core.test.DDCoreSpecification

import java.util.function.BiConsumer

import static datadog.trace.core.baggage.BaggagePropagator.BAGGAGE_KEY

class BaggagePropagatorTest extends DDCoreSpecification {
  BaggagePropagator propagator
  CarrierSetter setter
  Map<String, String> carrier
  Context context


  static class MapCarrierAccessor
    implements CarrierSetter<Map<String, String>>, CarrierVisitor<Map<String, String>> {
    @Override
    void set(Map<String, String> carrier, String key, String value) {
      if (carrier != null && key != null && value != null) {
        carrier.put(key, value);
      }
    }

    @Override
    void forEachKeyValue(Map<String, String> carrier, BiConsumer<String, String> visitor) {
      carrier.forEach(visitor);
    }
  }

  def setup() {
    this.propagator = new BaggagePropagator(true, true)
    setter = new MapCarrierAccessor()
    carrier = [:]
    context = Context.root()
  }

  def 'test baggage propagator context injection'() {
    setup:
    context = BaggageContext.create(baggageMap).storeInto(context)

    when:
    this.propagator.inject(context, carrier, setter)

    then:
//    1 * setter.set(carrier, BAGGAGE_KEY, baggageHeader)
    assert carrier[BAGGAGE_KEY] == baggageHeader

    where:
    baggageMap                                               | baggageHeader
    ["key1": "val1", "key2": "val2", "foo": "bar", "x": "y"] | "key1=val1,key2=val2,foo=bar,x=y"
    ['",;\\()/:<=>?@[]{}': '",;\\']                          | "%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C"
    [key1: "val1"]                                           | "key1=val1"
    [key1: "val1", key2: "val2"]                             | "key1=val1,key2=val2"
    [serverNode: "DF 28"]                                    | "serverNode=DF%2028"
    [userId: "Amélie"]                                       | "userId=Am%C3%A9lie"
    ["user!d(me)": "false"]                                  | "user!d%28me%29=false"
    ["abcdefg": "hijklmnopq♥"]                               | "abcdefg=hijklmnopq%E2%99%A5"
  }

  def "test baggage item limit"() {
    setup:
    injectSysConfig("trace.baggage.max.items", '2')
    propagator = new BaggagePropagator(true, true) //creating a new instance after injecting config
    context = BaggageContext.create(baggage).storeInto(context)

    when:
    this.propagator.inject(context, carrier, setter)

    then:
//    1 * setter.set(carrier, BAGGAGE_KEY, baggageHeader)
    assert carrier[BAGGAGE_KEY] == baggageHeader

    where:
    baggage                                    | baggageHeader
    [key1: "val1", key2: "val2"]               | "key1=val1,key2=val2"
    [key1: "val1", key2: "val2", key3: "val3"] | "key1=val1,key2=val2"
  }

  def "test baggage bytes limit"() {
    setup:
    injectSysConfig("trace.baggage.max.bytes", '20')
    propagator = new BaggagePropagator(true, true) //creating a new instance after injecting config
    context = BaggageContext.create(baggage).storeInto(context)

    when:
    this.propagator.inject(context, carrier, setter)

    then:
//    1 * setter.set(carrier, BAGGAGE_KEY, baggageHeader)
    assert carrier[BAGGAGE_KEY] == baggageHeader

    where:
    baggage                                    | baggageHeader
    [key1: "val1", key2: "val2"]               | "key1=val1,key2=val2"
    [key1: "val1", key2: "val2", key3: "val3"] | "key1=val1,key2=val2"
    ["abcdefg": "hijklmnopq♥"]                 | ""
  }

  def 'test tracing propagator context extractor'() {
    setup:
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

  def "extract invalid baggage headers"() {
    setup:
    def headers = [
      (BAGGAGE_KEY) : baggageHeader,
    ]

    when:
    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap())

    then:
    BaggageContext.fromContext(context) == null

    where:
    baggageHeader                                                       | _
    "no-equal-sign,foo=gets-dropped-because-previous-pair-is-malformed" | _
    "foo=gets-dropped-because-subsequent-pair-is-malformed,="           | _
    "=no-key"                                                           | _
    "no-value="                                                         | _
    ""                                                                  | _
    ",,"                                                                | _
    "="                                                                 | _
  }
}
