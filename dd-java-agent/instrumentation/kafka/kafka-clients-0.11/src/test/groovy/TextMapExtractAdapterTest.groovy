import com.google.common.io.BaseEncoding
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.instrumentation.kafka_clients.TextMapExtractAdapter
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders

import java.nio.charset.StandardCharsets

class TextMapExtractAdapterTest extends InstrumentationSpecification {

  def "check can decode base64 mangled headers"() {
    given:
    def base64 = BaseEncoding.base64().encode("foo".getBytes(StandardCharsets.UTF_8))
    def expectedValue = base64Decode ? "foo" : base64
    Headers headers = new RecordHeaders(new RecordHeader("key", base64.getBytes(StandardCharsets.UTF_8)))
    TextMapExtractAdapter adapter = new TextMapExtractAdapter(base64Decode)
    when:
    String extracted = null
    adapter.forEachKey(headers, new AgentPropagation.KeyClassifier() {
        @Override
        boolean accept(String key, String value) {
          extracted = value
          return false
        }
      })

    then:
    extracted == expectedValue

    where:
    base64Decode << [true, false]
  }
}
