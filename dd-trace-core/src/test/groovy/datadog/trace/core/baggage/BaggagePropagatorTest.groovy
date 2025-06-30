package datadog.trace.core.baggage

import datadog.context.Context
import datadog.context.propagation.CarrierSetter
import datadog.context.propagation.CarrierVisitor
import datadog.trace.bootstrap.instrumentation.api.Baggage
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.test.util.DDSpecification

import java.util.function.BiConsumer

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_BAGGAGE_MAX_BYTES
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_BAGGAGE_MAX_ITEMS
import static datadog.trace.core.baggage.BaggagePropagator.BAGGAGE_KEY

class BaggagePropagatorTest extends DDSpecification {
  BaggagePropagator propagator
  CarrierSetter setter
  Map<String, String> carrier
  Context context

  static class MapCarrierAccessor
  implements CarrierSetter<Map<String, String>>, CarrierVisitor<Map<String, String>> {
    @Override
    void set(Map<String, String> carrier, String key, String value) {
      if (carrier != null && key != null && value != null) {
        carrier.put(key, value)
      }
    }

    @Override
    void forEachKeyValue(Map<String, String> carrier, BiConsumer<String, String> visitor) {
      carrier.forEach(visitor)
    }
  }

  def setup() {
    this.propagator = new BaggagePropagator(true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, DEFAULT_TRACE_BAGGAGE_MAX_BYTES)
    this.setter = new MapCarrierAccessor()
    this.carrier = [:]
    this.context = Context.root()
  }

  def 'test baggage propagator context injection'() {
    setup:
    this.context = Baggage.create(baggageMap).storeInto(this.context)

    when:
    this.propagator.inject(context, carrier, setter)

    then:
    assert carrier[BAGGAGE_KEY] == baggageHeader

    where:
    baggageMap                                     | baggageHeader
    ["key1": "val1", "key2": "val2", "foo": "bar"] | "key1=val1,key2=val2,foo=bar"
    ['",;\\()/:<=>?@[]{}': '",;\\']                | "%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C"
    [key1: "val1"]                                 | "key1=val1"
    [key1: "val1", key2: "val2"]                   | "key1=val1,key2=val2"
    [serverNode: "DF 28"]                          | "serverNode=DF%2028"
    [userId: "Amélie"]                             | "userId=Am%C3%A9lie"
    ["user!d(me)": "false"]                        | "user!d%28me%29=false"
    ["abcdefg": "hijklmnopq♥"]                     | "abcdefg=hijklmnopq%E2%99%A5"
  }

  def "test baggage inject item limit"() {
    setup:
    propagator = new BaggagePropagator(true, true, 2, DEFAULT_TRACE_BAGGAGE_MAX_BYTES) //creating a new instance after injecting config
    context = Baggage.create(baggage).storeInto(context)

    when:
    this.propagator.inject(context, carrier, setter)

    then:
    assert carrier[BAGGAGE_KEY] == baggageHeader

    where:
    baggage                                    | baggageHeader
    [key1: "val1", key2: "val2"]               | "key1=val1,key2=val2"
    [key1: "val1", key2: "val2", key3: "val3"] | "key1=val1,key2=val2"
  }

  def "test baggage inject bytes limit"() {
    setup:
    propagator = new BaggagePropagator(true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, 20) //creating a new instance after injecting config
    context = Baggage.create(baggage).storeInto(context)

    when:
    this.propagator.inject(context, carrier, setter)

    then:
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
    Baggage.fromContext(context).asMap() == baggageMap

    where:
    baggageHeader                                                      | baggageMap
    "key1=val1,key2=val2,foo=bar"                                      | ["key1": "val1", "key2": "val2", "foo": "bar"]
    "%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C" | ['",;\\()/:<=>?@[]{}': '",;\\']
  }

  def "test extracting non ASCII headers"() {
    setup:
    def headers = [
      (BAGGAGE_KEY) : "key1=vallée,clé2=value",
    ]

    when:
    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap())
    def baggage = Baggage.fromContext(context)

    then: 'non ASCII values data are still accessible as part of the API'
    baggage != null
    baggage.asMap().get('key1') == 'vallée'
    baggage.asMap().get('clé2') == 'value'
    baggage.w3cHeader == null


    when:
    this.propagator.inject(Context.root().with(baggage), carrier, setter)

    then: 'baggage are URL encoded if not valid, even if not modified'
    assert carrier[BAGGAGE_KEY] == 'key1=vall%C3%A9e,cl%C3%A92=value'
  }

  def "extract invalid baggage headers"() {
    setup:
    def headers = [
      (BAGGAGE_KEY) : baggageHeader,
    ]

    when:
    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap())

    then:
    Baggage.fromContext(context) == null

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

  def "test baggage cache"(){
    setup:
    def headers = [
      (BAGGAGE_KEY) : baggageHeader,
    ]

    when:
    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap())

    then:
    Baggage baggageContext = Baggage.fromContext(context)
    baggageContext.w3cHeader == cachedString

    where:
    baggageHeader                 | cachedString
    "key1=val1,key2=val2,foo=bar" | "key1=val1,key2=val2,foo=bar"
    '";\\()/:<=>?@[]{}=";\\'      | null
  }

  def "test baggage cache items limit"(){
    setup:
    propagator = new BaggagePropagator(true, true, 2, DEFAULT_TRACE_BAGGAGE_MAX_BYTES) //creating a new instance after injecting config
    def headers = [
      (BAGGAGE_KEY) : baggageHeader,
    ]

    when:
    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap())

    then:
    Baggage baggageContext = Baggage.fromContext(context)
    baggageContext.getW3cHeader() as String == cachedString

    where:
    baggageHeader                             | cachedString
    "key1=val1,key2=val2"                     | "key1=val1,key2=val2"
    "key1=val1,key2=val2,key3=val3"           | "key1=val1,key2=val2"
    "key1=val1,key2=val2,key3=val3,key4=val4" | "key1=val1,key2=val2"
  }

  def "test baggage cache bytes limit"(){
    setup:
    propagator = new BaggagePropagator(true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, 20) //creating a new instance after injecting config
    def headers = [
      (BAGGAGE_KEY) : baggageHeader,
    ]

    when:
    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap())

    then:
    Baggage baggageContext = Baggage.fromContext(context)
    baggageContext.getW3cHeader() as String == cachedString

    where:
    baggageHeader                   | cachedString
    "key1=val1,key2=val2"           | "key1=val1,key2=val2"
    "key1=val1,key2=val2,key3=val3" | "key1=val1,key2=val2"
  }
}
