import datadog.trace.instrumentation.kafka_common.Utils
import java.nio.charset.StandardCharsets
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import spock.lang.Specification

class UtilsTest extends Specification {

  def "utf8Length matches String.getBytes(UTF_8).length for #name"() {
    expect:
    Utils.utf8Length(value) == value.getBytes(StandardCharsets.UTF_8).length

    where:
    name                | value
    "empty"             | ""
    "ascii"             | "dd-pathway-ctx-base64"
    "two byte"          | "caf\u00e9 \u07ff"
    "three byte"        | "\u0800 \u20ac \uffff"
    "surrogate pair"    | "a\uD83D\uDE00b"
    "lone high"         | "a\uD83Db"
    "lone low"          | "a\uDE00b"
    "high at end"       | "ab\uD83D"
    "reversed pair"     | "\uDE00\uD83D"
    "two highs, pair"   | "\uD83D\uD83D\uDE00"
  }

  def "keyUtf8Length matches String.getBytes(UTF_8).length for #name"() {
    expect:
    Utils.keyUtf8Length(value) == value.getBytes(StandardCharsets.UTF_8).length
    Utils.keyUtf8Length(new String(value)) == value.getBytes(StandardCharsets.UTF_8).length

    where:
    name                  | value
    "empty"               | ""
    "ascii"               | "dd-pathway-ctx-base64"
    "two byte"            | "caf\u00e9 \u07ff"
    "three byte"          | "\u0800 \u20ac \uffff"
    "surrogate pair"      | "a\uD83D\uDE00b"
    "lone high"           | "a\uD83Db"
    "lone low"            | "a\uDE00b"
    "long ascii"          | "k" * 73
    "long non-ascii"      | "\u00e9" * 65
    "at cap"              | "k" * 64
  }

  def "keyUtf8Length returns exact lengths for keys sharing a slot"() {
    setup:
    def slot = {
      String k -> int h = k.hashCode(); (h ^ (h >>> 16)) & 127
    }
    String a = "key-0"
    String b = null
    for (int i = 1; b == null; i++) {
      def candidate = "key-" + i + "\u00e9"
      if (slot(candidate) == slot(a)) {
        b = candidate
      }
    }

    expect:
    4.times {
      assert Utils.keyUtf8Length(a) == a.getBytes(StandardCharsets.UTF_8).length
      assert Utils.keyUtf8Length(b) == b.getBytes(StandardCharsets.UTF_8).length
    }
  }

  def "computePayloadSizeBytes sums header keys, values and serialized sizes"() {
    setup:
    def headers = new RecordHeaders()
    headers.add(new RecordHeader("dd-pathway-ctx-base64", "abcdef".getBytes(StandardCharsets.UTF_8)))
    headers.add(new RecordHeader("caf\u00e9", "xy".getBytes(StandardCharsets.UTF_8)))
    headers.add(new RecordHeader("null-value", null))
    headers.add(new RecordHeader("dd-pathway-ctx-base64", "z".getBytes(StandardCharsets.UTF_8)))
    headers.add(new RecordHeader("k" * 70, "12345".getBytes(StandardCharsets.UTF_8)))
    def record = new ConsumerRecord("topic", 0, 0L, 0L, TimestampType.CREATE_TIME, 0L, 7, 11, "k", "v", headers)
    long expected = 7 + 11
    for (def h : headers) {
      expected += (h.value() == null ? 0 : h.value().length) + h.key().getBytes(StandardCharsets.UTF_8).length
    }

    expect:
    Utils.computePayloadSizeBytes(record) == expected
    Utils.computePayloadSizeBytes(record) == expected
  }
}
