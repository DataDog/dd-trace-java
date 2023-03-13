package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class DD64bTraceIdTest extends DDSpecification {
  def "convert 64-bit ids from/to long #longId and check strings"() {
    when:
    final ddid = DD64bTraceId.from(longId)

    then:
    ddid == expectedId
    ddid.toLong() == longId
    ddid.toString() == expectedString
    ddid.toHexString() == expectedHex
    ddid.toHexStringOrOriginal() == expectedHex

    where:
    longId         | expectedId                        | expectedString         | expectedHex
    0              | DD64bTraceId.ZERO                 | "0"                    | "0"
    1              | DD64bTraceId.ONE                  | "1"                    | "1"
    -1             | DD64bTraceId.MAX                  | "18446744073709551615" | "f" * 16
    Long.MAX_VALUE | DD64bTraceId.from(Long.MAX_VALUE) | "9223372036854775807"  | "7" + "f" * 15
    Long.MIN_VALUE | DD64bTraceId.from(Long.MIN_VALUE) | "9223372036854775808"  | "8" + "0" * 15
  }

  def "convert 64-bits ids from/to String representation: #stringId"() {
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

  def "fail parsing illegal 64-bits id String representation: #stringId"() {
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
    if (hexId.length() > 1) {
      hexId = hexId.replaceAll("^0+", "") // drop leading zeros
    }
    ddid.toHexString() == hexId
    ddid.toHexStringOrOriginal() == hexId
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
    DDTraceId.fromHex(hexId)

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
}
