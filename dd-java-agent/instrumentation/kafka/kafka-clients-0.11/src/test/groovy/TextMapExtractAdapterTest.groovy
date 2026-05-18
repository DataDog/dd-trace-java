import com.google.common.io.BaseEncoding
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.instrumentation.kafka_clients.KafkaDecorator
import datadog.trace.instrumentation.kafka_clients.TextMapExtractAdapter
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders

import java.nio.ByteBuffer
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

  def "extractTimeInQueueStart returns 0 when header is absent"() {
    given:
    Headers headers = new RecordHeaders()
    TextMapExtractAdapter adapter = new TextMapExtractAdapter(false)

    expect:
    adapter.extractTimeInQueueStart(headers) == 0
  }

  def "extractTimeInQueueStart parses raw binary long (base64 disabled)"() {
    given:
    long ts = 1747587600000L
    byte[] binaryValue = ByteBuffer.allocate(8).putLong(ts).array()
    Headers headers = new RecordHeaders(new RecordHeader(KafkaDecorator.KAFKA_PRODUCED_KEY, binaryValue))
    TextMapExtractAdapter adapter = new TextMapExtractAdapter(false)

    expect:
    adapter.extractTimeInQueueStart(headers) == ts
  }

  def "extractTimeInQueueStart parses base64-encoded binary (base64 enabled)"() {
    given:
    long ts = 1747587600000L
    byte[] binaryValue = ByteBuffer.allocate(8).putLong(ts).array()
    byte[] base64Value = Base64.getEncoder().encode(binaryValue)
    Headers headers = new RecordHeaders(new RecordHeader(KafkaDecorator.KAFKA_PRODUCED_KEY, base64Value))
    TextMapExtractAdapter adapter = new TextMapExtractAdapter(true)

    expect:
    adapter.extractTimeInQueueStart(headers) == ts
  }

  def "extractTimeInQueueStart falls back to binary when base64 decode fails (base64 enabled)"() {
    given:
    long ts = 1747587600000L
    byte[] binaryValue = ByteBuffer.allocate(8).putLong(ts).array()
    Headers headers = new RecordHeaders(new RecordHeader(KafkaDecorator.KAFKA_PRODUCED_KEY, binaryValue))
    TextMapExtractAdapter adapter = new TextMapExtractAdapter(true)

    expect:
    adapter.extractTimeInQueueStart(headers) == ts
  }

  def "extractTimeInQueueStart returns 0 for garbage bytes (base64 disabled)"() {
    given:
    byte[] garbage = [1, 2, 3] as byte[]
    Headers headers = new RecordHeaders(new RecordHeader(KafkaDecorator.KAFKA_PRODUCED_KEY, garbage))
    TextMapExtractAdapter adapter = new TextMapExtractAdapter(false)

    expect:
    adapter.extractTimeInQueueStart(headers) == 0
  }
}
