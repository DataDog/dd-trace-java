package datadog.trace.core.propagation

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.DynamicConfig
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopPathwayContext
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification


import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.core.propagation.BaggageHttpCodec.*


class BaggageHttpInjectorTest extends DDCoreSpecification {

  private HttpCodec.Injector injector = newInjector(["some-baggage-key":"SOME_CUSTOM_HEADER"])
  private ListWriter writer
  private CoreTracer tracer
  private Map<String, String> carrier

  void setup() {
    writer = new ListWriter()
    tracer = tracerBuilder().writer(writer).build()
    carrier = Mock()
  }

  def "test baggage injection and encoding"() {
    setup:
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDTraceId.from("1"),
      DDSpanId.from("2"),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      "fakeOrigin",
      baggage,
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-4,_dd.p.anytag=value"))
    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(BAGGAGE_KEY, baggageHeaders)
    0 * _

    cleanup:
    tracer.close()

    where:
    baggage                         | baggageHeaders
    [key1: "val1"]                  | "key1=val1"
    [key1: "val1", key2: "val2"]    | "key1=val1,key2=val2"
    [serverNode: "DF 28"]           | "serverNode=DF%2028"
    [userId: "Amélie"]              | "userId=Am%C3%A9lie"
    ['",;\\()/:<=>?@[]{}': '",;\\'] | "%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C"
    ["user!d(me)": "false"]         | "user!d%28me%29=false"
    ["abcdefg": "hijklmnopq♥"]       | "abcdefg=hijklmnopq%E2%99%A5"
  }

  def "test baggage item limit"() {
    setup:
    injectSysConfig("trace.baggage.max.items", '2')
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDTraceId.from("1"),
      DDSpanId.from("2"),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      "fakeOrigin",
      baggage,
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-4,_dd.p.anytag=value"))

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(BAGGAGE_KEY, baggageHeaders)
    0 * _

    cleanup:
    tracer.close()

    where:
    baggage                                    | baggageHeaders
    [key1: "val1", key2: "val2"]               | "key1=val1,key2=val2"
    [key1: "val1", key2: "val2", key3: "val3"] | "key1=val1,key2=val2"
  }

  def "test baggage bytes limit"() {
    setup:
    injectSysConfig("trace.baggage.max.bytes", '20')
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDTraceId.from("1"),
      DDSpanId.from("2"),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      "fakeOrigin",
      baggage,
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-4,_dd.p.anytag=value"))

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(BAGGAGE_KEY, baggageHeaders)
    0 * _

    cleanup:
    tracer.close()

    where:
    baggage                                    | baggageHeaders
    [key1: "val1", key2: "val2"]               | "key1=val1,key2=val2"
    [key1: "val1", key2: "val2", key3: "val3"] | "key1=val1,key2=val2"
    ["abcdefg": "hijklmnopq♥"]                 | ""
  }
}
