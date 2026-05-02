package datadog.trace.api.git

import static java.nio.charset.StandardCharsets.UTF_16
import static java.nio.charset.StandardCharsets.UTF_8

import spock.lang.Specification

class RawParseUtilsTest extends Specification {

  def "test decode no fallback"() {
    setup:
    def str = new String("some-string".getBytes(), UTF_16)
    def encoded = str.getBytes(UTF_8)

    when:
    def decoded = RawParseUtils.decode(encoded, 0, encoded.length)

    then:
    decoded == str
  }

  def "test extract binary string"() {
    setup:
    def str = new String("some-string".getBytes(), UTF_8)
    def encoded = str.getBytes(UTF_8)

    when:
    def decoded = RawParseUtils.extractBinaryString(encoded, 0, encoded.length)

    then:
    decoded == str
  }

  def "test parse long base 10"() {
    when:
    def parsedLong = RawParseUtils.parseLongBase10(bArray, 0)

    then:
    parsedLong == expectedLong

    where:
    bArray                   | expectedLong
    "  1000000000000".bytes  | 1000000000000L
    "  +1000000000000".bytes | 1000000000000L
    "  -1000000000000".bytes | -1000000000000L
  }

  def "test parse int base 10"() {
    when:
    def parsedLong = RawParseUtils.parseBase10(bArray, 0)

    then:
    parsedLong == expectedInt

    where:
    bArray        | expectedInt
    "  10".bytes  | 10
    "  +10".bytes | 10
    "  -10".bytes | -10
  }
}
