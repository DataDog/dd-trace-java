import datadog.trace.instrumentation.kafka_common.Utils
import java.nio.charset.StandardCharsets
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
}
