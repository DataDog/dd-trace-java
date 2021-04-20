package datadog.trace.api


import datadog.trace.test.util.DDSpecification

class DDIdTest extends DDSpecification {
  def "convert ids from/to long and check strings and BigInteger"() {
    when:
    final ddid = DDId.from(longId)

    then:
    ddid == expectedId
    ddid.toLong() == longId
    ddid.toString() == expectedString
    ddid.toHexString() == expectedHex
    ddid.toHexStringOrOriginal() == expectedHex

    where:
    longId         | expectedId                | expectedString         | expectedHex
    0              | DDId.ZERO                 | "0"                    | "0"
    1              | DDId.ONE                  | "1"                    | "1"
    -1             | DDId.MAX                  | "18446744073709551615" | "f" * 16
    Long.MAX_VALUE | DDId.from(Long.MAX_VALUE) | "9223372036854775807"  | "7" + "f" * 15
    Long.MIN_VALUE | DDId.from(Long.MIN_VALUE) | "9223372036854775808"  | "8" + "0" * 15
  }

  def "convert ids from/to String"() {
    when:
    final ddid = DDId.from(stringId)

    then:
    ddid == expectedId
    ddid.toString() == stringId

    where:
    stringId                                        | expectedId
    "0"                                             | DDId.ZERO
    "1"                                             | DDId.ONE
    "18446744073709551615"                          | DDId.MAX
    "${Long.MAX_VALUE}"                             | DDId.from(Long.MAX_VALUE)
    "${BigInteger.valueOf(Long.MAX_VALUE).plus(1)}" | DDId.from(Long.MIN_VALUE)
  }

  def "fail on illegal String"() {
    when:
    DDId.from(stringId)

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

  def "convert ids from/to hex String"() {
    when:
    final ddid = DDId.fromHex(hexId)
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
    "0"                      | DDId.ZERO
    "1"                      | DDId.ONE
    "f" * 16                 | DDId.MAX
    "7" + "f" * 15           | DDId.from(Long.MAX_VALUE)
    "8" + "0" * 15           | DDId.from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15 | DDId.from(Long.MIN_VALUE)
    "cafebabe"               | DDId.from(3405691582)
    "123456789abcdef"        | DDId.from(81985529216486895)
  }

  def "fail on illegal hex String"() {
    when:
    DDId.fromHex(hexId)

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

  def "generate id with #idGenerator"() {
    when:
    final ddid = idGenerator.generate()

    then:
    !ddid.equals(null)
    !ddid.equals("foo")
    ddid != DDId.ZERO
    ddid.equals(ddid)
    ddid.hashCode() == (int) (ddid.toLong() ^ (ddid.toLong() >>> 32))

    where:
    idGenerator << IdGenerationStrategy.values()
  }

  def "convert ids from/to hex String while keeping the original"() {
    when:
    final ddid = DDId.fromHexWithOriginal(hexId)

    then:
    ddid == expectedId
    ddid.toHexStringOrOriginal() == hexId

    where:
    hexId                    | expectedId
    "0"                      | DDId.ZERO
    "1"                      | DDId.ONE
    "f" * 16                 | DDId.MAX
    "7" + "f" * 15           | DDId.from(Long.MAX_VALUE)
    "8" + "0" * 15           | DDId.from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15 | DDId.from(Long.MIN_VALUE)
    "cafebabe"               | DDId.from(3405691582)
    "123456789abcdef"        | DDId.from(81985529216486895)
  }

  def "convert ids from/to hex String and truncate to 64 bits while keeping the original"() {
    when:
    final ddid = DDId.fromHexTruncatedWithOriginal(hexId)

    then:
    ddid == expectedId
    ddid.toHexStringOrOriginal() == hexId

    where:
    hexId                          | expectedId
    "000"                          | DDId.ZERO
    "0001"                         | DDId.ONE
    "f" * 16                       | DDId.MAX
    "7" + "f" * 15                 | DDId.from(Long.MAX_VALUE)
    "8" + "0" * 15                 | DDId.from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15       | DDId.from(Long.MIN_VALUE)
    "1" * 8 + "0" * 8 + "cafebabe" | DDId.from(3405691582)
    "1" * 12 + "0123456789abcdef"  | DDId.from(81985529216486895)
  }
}
