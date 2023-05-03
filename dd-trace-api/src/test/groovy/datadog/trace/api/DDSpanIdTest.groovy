package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class DDSpanIdTest extends DDSpecification {

  def "convert ids from/to String #stringId"() {
    when:
    final ddid = DDSpanId.from(stringId)

    then:
    ddid == expectedId
    DDSpanId.toString(ddid) == stringId

    where:
    stringId                                        | expectedId
    "0"                                             | 0
    "1"                                             | 1
    "18446744073709551615"                          | DDSpanId.MAX
    "${Long.MAX_VALUE}"                             | Long.MAX_VALUE
    "${BigInteger.valueOf(Long.MAX_VALUE).plus(1)}" | Long.MIN_VALUE
  }

  def "fail on illegal String"() {
    when:
    DDSpanId.from(stringId)

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
    final ddid = DDSpanId.fromHex(hexId)
    final padded16 = hexId.length() <= 16 ?
      ("0" * 16).substring(0, 16 - hexId.length()) + hexId :
      hexId.substring(hexId.length() - 16, hexId.length())

    then:
    ddid == expectedId
    if (hexId.length() > 1) {
      hexId = hexId.replaceAll("^0+", "") // drop leading zeros
    }
    DDSpanId.toHexString(ddid) == hexId
    DDSpanId.toHexStringPadded(ddid) == padded16

    where:
    hexId                    | expectedId
    "0"                      | 0
    "1"                      | 1
    "f" * 16                 | DDSpanId.MAX
    "7" + "f" * 15           | Long.MAX_VALUE
    "8" + "0" * 15           | Long.MIN_VALUE
    "0" * 4 + "8" + "0" * 15 | Long.MIN_VALUE
    "cafebabe"               | 3405691582
    "123456789abcdef"        | 81985529216486895
  }

  def "convert ids from part of hex String"() {
    when:
    Long ddid = null
    try {
      ddid = DDSpanId.fromHex(hexId, start, length, lcOnly)
    } catch (NumberFormatException ignored) {
    }

    then:
    if (expectedId) {
      assert ddid == expectedId
    } else {
      assert !ddid
    }

    where:
    hexId            | start| length | lcOnly | expectedId
    null             |  1   |  1     | false  | null
    ""               |  1   |  1     | false  | null
    "00"             | -1   |  1     | false  | null
    "00"             |  0   |  0     | false  | null
    "00"             |  0   |  1     | false  | DDSpanId.ZERO
    "00"             |  1   |  1     | false  | DDSpanId.ZERO
    "00"             |  1   |  1     | false  | DDSpanId.ZERO
    "f" * 16         |  0   | 16     | true   | DDSpanId.MAX
    "f" * 12 + "Ffff"|  0   | 16     | true   | null
    "f" * 12 + "Ffff"|  0   | 16     | false  | DDSpanId.MAX
  }

  def "fail on illegal hex String"() {
    when:
    DDSpanId.fromHex(hexId)

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

  def "generate id with #strategyName"() {
    when:
    def strategy = IdGenerationStrategy.fromName(strategyName)
    def spanIds = (0..32768).collect { strategy.generateSpanId() }
    Set<Long> checked = new HashSet<>()

    then:
    spanIds.forEach { spanId ->
      assert spanId != 0
      assert !checked.contains(spanId)
      checked.add(spanId)
    }

    where:
    strategyName << ["RANDOM", "SEQUENTIAL", "SECURE_RANDOM"]
  }
}
