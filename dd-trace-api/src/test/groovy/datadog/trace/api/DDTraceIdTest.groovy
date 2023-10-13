package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class DDTraceIdTest extends DDSpecification {
  def "convert 64-bit ids from/to long #longId and check strings"() {
    when:
    final ddid = DD64bTraceId.from(longId)
    final defaultDdid = DDTraceId.from(longId)

    then:
    ddid == expectedId
    ddid == defaultDdid
    ddid.toLong() == longId
    ddid.toHighOrderLong() == 0L
    ddid.toString() == expectedString
    ddid.toHexString() == expectedHex

    where:
    longId         | expectedId                        | expectedString         | expectedHex
    0              | DD64bTraceId.ZERO                 | "0"                    | "0" * 32
    1              | DD64bTraceId.ONE                  | "1"                    | "0" * 31 + "1"
    -1             | DD64bTraceId.MAX                  | "18446744073709551615" | "0" * 16 + "f" * 16
    Long.MAX_VALUE | DD64bTraceId.from(Long.MAX_VALUE) | "9223372036854775807"  | "0" * 16 + "7" + "f" * 15
    Long.MIN_VALUE | DD64bTraceId.from(Long.MIN_VALUE) | "9223372036854775808"  | "0" * 16 + "8" + "0" * 15
  }

  def "convert 64-bit ids from/to String representation: #stringId"() {
    when:
    final ddid = DD64bTraceId.from(stringId)

    then:
    ddid == expectedId
    ddid.toString() == stringId

    where:
    stringId                                        | expectedId
    "0"                                             | DD64bTraceId.ZERO
    "1"                                             | DD64bTraceId.ONE
    "18446744073709551615"                          | DD64bTraceId.MAX
    "${Long.MAX_VALUE}"                             | DD64bTraceId.from(Long.MAX_VALUE)
    "${BigInteger.valueOf(Long.MAX_VALUE).plus(1)}" | DD64bTraceId.from(Long.MIN_VALUE)
  }

  def "fail parsing illegal 64-bit id String representation: #stringId"() {
    when:
    DD64bTraceId.from(stringId)

    then:
    thrown NumberFormatException

    where:
    stringId << [
      null,
      "",
      "-1",
      "18446744073709551616",
      "18446744073709551625",
      "184467440737095516150",
      "18446744073709551a1",
      "184467440737095511a"
    ]
  }

  def "convert 64-bit ids from/to hex String representation: #hexId"() {
    when:
    final ddid = DD64bTraceId.fromHex(hexId)
    final padded16 = hexId.length() <= 16 ?
      ("0" * 16).substring(0, 16 - hexId.length()) + hexId :
      hexId.substring(hexId.length() - 16, hexId.length())
    final padded32 = ("0" * 32).substring(0, 32 - hexId.length()) + hexId

    then:
    ddid == expectedId
    ddid.toHexString() == padded32
    ddid.toHexStringPadded(16) == padded16
    ddid.toHexStringPadded(32) == padded32

    where:
    hexId                    | expectedId
    "0"                      | DD64bTraceId.ZERO
    "1"                      | DD64bTraceId.ONE
    "f" * 16                 | DD64bTraceId.MAX
    "7" + "f" * 15           | DD64bTraceId.from(Long.MAX_VALUE)
    "8" + "0" * 15           | DD64bTraceId.from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15 | DD64bTraceId.from(Long.MIN_VALUE)
    "cafebabe"               | DD64bTraceId.from(3405691582)
    "123456789abcdef"        | DD64bTraceId.from(81985529216486895)
  }

  def "fail parsing illegal 64-bit hexadecimal String representation: #hexId"() {
    when:
    DD64bTraceId.fromHex(hexId)

    then:
    thrown NumberFormatException

    where:
    hexId << [
      null,
      "",
      "-1",
      "1" + "0" * 16,
      "f" * 14 + "zf",
      "f" * 15 + "z"
    ]
  }

  def "convert 128-bit ids from/to hexadecimal String representation #hexId"() {
    when:
    def parsedId = DD128bTraceId.fromHex(hexId)
    def id = DD128bTraceId.from(high, low)
    def paddedHexId = hexId.padLeft(32, '0')

    then:
    id == parsedId
    parsedId.toHexString() == paddedHexId
    parsedId.toHexStringPadded(16) == paddedHexId.substring(16, 32)
    parsedId.toHexStringPadded(32) == paddedHexId
    parsedId.toLong() == low
    parsedId.toHighOrderLong() == high
    parsedId.toString() == Long.toUnsignedString(low)

    where:
    high                 | low                  | hexId
    Long.MIN_VALUE       | Long.MIN_VALUE       | "8" + "0" * 15 + "8" + "0" * 15
    Long.MIN_VALUE       | 1L                   | "8" + "0" * 15 + "0" * 15 + "1"
    Long.MIN_VALUE       | Long.MAX_VALUE       | "8" + "0" * 15 + "7" + "f" * 15
    1L                   | Long.MIN_VALUE       | "0" * 15 + "1" + "8" + "0" * 15
    1L                   | 1L                   | "0" * 15 + "1" + "0" * 15 + "1"
    1L                   | Long.MAX_VALUE       | "0" * 15 + "1" + "7" + "f" * 15
    Long.MAX_VALUE       | Long.MIN_VALUE       | "7" + "f" * 15 + "8" + "0" * 15
    Long.MAX_VALUE       | 1L                   | "7" + "f" * 15 + "0" * 15 + "1"
    Long.MAX_VALUE       | Long.MAX_VALUE       | "7" + "f" * 15 + "7" + "f" * 15
    0L                   | 0L                   | "0" * 1
    0L                   | 0L                   | "0" * 16
    0L                   | 0L                   | "0" * 17
    0L                   | 0L                   | "0" * 32
    0L                   | 15L                  | "f" * 1
    0L                   | -1L                  | "f" * 16
    15L                  | -1L                  | "f" * 17
    -1L                  | -1L                  | "f" * 32
    1311768467463790320L | 1311768467463790320L | "123456789abcdef0123456789abcdef0"
  }

  def "fail parsing illegal 128-bit id hexadecimal String representation: #hexId"() {
    when:
    DD128bTraceId.fromHex(hexId)

    then:
    thrown NumberFormatException

    where:
    hexId << [
      null,
      "",
      "-1",
      "-A",
      "1" * 33,
      "123ABC",
      "123abcg",
    ]
  }

  def "fail parsing illegal 128-bit id hexadecimal String representation from partial String: #hexId"() {
    when:
    DD128bTraceId.fromHex(hexId, start, length, lowerCaseOnly)

    then:
    thrown NumberFormatException

    where:
    hexId              | start | length | lowerCaseOnly
    // Null string
    null               | 0     | 0      | true
    // Empty string
    ""                 | 0     | 0      | true
    // Out of bound
    "123456789abcdef0" | 0     | 17     | true
    "123456789abcdef0" | 7     | 10     | true
    "123456789abcdef0" | 17    | 0      | true
    // Invalid characters
    "-1"               | 0     | 1      | true
    "-a"               | 0     | 1      | true
    "123abcg"          | 0     | 7      | true
    // Invalid case
    "A"                | 0     | 1      | true
    "123ABC"           | 0     | 6      | true
    // Too long id
    "1" * 33           | 0     | 33     | true
  }

  def "check ZERO constant initialization"() {
    when:
    def zero = DDTraceId.ZERO
    def fromZero = DDTraceId.from(0)

    then:
    zero != null
    zero.is(fromZero)
  }
}
