package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DynamicConfig
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
//import datadog.trace.core.baggage.BaggageHttpCodec
import datadog.trace.test.util.DDSpecification
//import static datadog.trace.core.baggage.BaggageHttpCodec.BAGGAGE_KEY

class BaggageHttpExtractorTest extends DDSpecification {

  private DynamicConfig dynamicConfig
  private HttpCodec.Extractor _extractor

  private HttpCodec.Extractor getExtractor() {
    _extractor ?: (_extractor = createExtractor(Config.get()))
  }

  private HttpCodec.Extractor createExtractor(Config config) {
    BaggageHttpCodec.newExtractor(config, { dynamicConfig.captureTraceConfig() })
  }

  void setup() {
    dynamicConfig = DynamicConfig.create()
      .apply()
  }

  void cleanup() {
    extractor.cleanup()
  }

  def "extract valid baggage headers"() {
    setup:
    def extractor = createExtractor(Config.get())
    def headers = [
      (BAGGAGE_KEY) : baggageHeader,
    ]

    when:
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.baggage == baggageMap

    cleanup:
    extractor.cleanup()

    where:
    baggageHeader                                                      | baggageMap
    "key1=val1,key2=val2,foo=bar,x=y"                                  | ["key1": "val1", "key2": "val2", "foo": "bar", "x": "y"]
    "%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C" | ['",;\\()/:<=>?@[]{}': '",;\\']
  }

  def "extract invalid baggage headers"() {
    setup:
    def extractor = createExtractor(Config.get())
    def headers = [
      (BAGGAGE_KEY) : baggageHeader,
    ]

    when:
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context == null

    cleanup:
    extractor.cleanup()

    where:
    baggageHeader                                                       | baggageMap
    "no-equal-sign,foo=gets-dropped-because-previous-pair-is-malformed" | []
    "foo=gets-dropped-because-subsequent-pair-is-malformed,=" | []
    "=no-key"       | []
    "no-value=" | []
  }
}
