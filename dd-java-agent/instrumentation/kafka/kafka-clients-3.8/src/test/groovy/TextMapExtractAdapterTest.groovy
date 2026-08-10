import com.google.common.io.BaseEncoding
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.instrumentation.kafka_clients38.TextMapExtractAdapter
import java.nio.charset.StandardCharsets
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders

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

  def "invalid base64 header is skipped and subsequent valid headers are still processed"() {
    given:
    def validBase64 = BaseEncoding.base64().encode("bar".getBytes(StandardCharsets.UTF_8))
    Headers headers = new RecordHeaders([
      new RecordHeader("bad-key", "not-valid-base64!@#".getBytes(StandardCharsets.UTF_8)),
      new RecordHeader("good-key", validBase64.getBytes(StandardCharsets.UTF_8))
    ])
    TextMapExtractAdapter adapter = new TextMapExtractAdapter(true)
    when:
    Map<String, String> extracted = [:]
    adapter.forEachKey(headers, new AgentPropagation.KeyClassifier() {
        @Override
        boolean accept(String key, String value) {
          extracted[key] = value
          return true
        }
      })
    then:
    noExceptionThrown()
    !extracted.containsKey("bad-key")
    extracted["good-key"] == "bar"
  }
}
