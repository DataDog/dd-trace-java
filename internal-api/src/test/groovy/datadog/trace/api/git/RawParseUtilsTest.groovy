package datadog.trace.api.git


import spock.lang.Specification

import java.nio.charset.StandardCharsets

class RawParseUtilsTest extends Specification {

  def "test decode no fallback"() {
    setup:
    def str = new String("some-string".getBytes(), StandardCharsets.UTF_16)
    def encoded = str.bytes

    when:
    def decoded = RawParseUtils.decode(encoded, 0, encoded.length)

    then:
    decoded == str
  }

  def "test extract binary string"() {
    setup:
    def str = new String("some-string".getBytes(), StandardCharsets.UTF_8)
    def encoded = str.bytes

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
