package datadog.trace.api


import datadog.trace.test.util.DDSpecification

abstract class DDIdTest<ID extends DDId> extends DDSpecification {

  abstract ID zero()
  abstract ID one()
  abstract ID max()
  abstract ID from(long id)
  abstract ID from(String id)
  abstract ID fromHex(String id)
  abstract ID generate(IdGenerationStrategy strategy)

  def "convert ids from/to long and check strings and BigInteger"() {
    when:
    final ddid = from(longId)

    then:
    ddid == expectedId
    ddid.toLong() == longId
    ddid.toString() == expectedString
    ddid.toHexString() == expectedHex
    ddid.toHexStringOrOriginal() == expectedHex

    where:
    longId         | expectedId           | expectedString         | expectedHex
    0              | zero()               | "0"                    | "0"
    1              | one()                | "1"                    | "1"
    -1             | max()                | "18446744073709551615" | "f" * 16
    Long.MAX_VALUE | from(Long.MAX_VALUE) | "9223372036854775807"  | "7" + "f" * 15
    Long.MIN_VALUE | from(Long.MIN_VALUE) | "9223372036854775808"  | "8" + "0" * 15
  }

  def "convert ids from/to String"() {
    when:
    final ddid = from(stringId)

    then:
    ddid == expectedId
    ddid.toString() == stringId

    where:
    stringId                                        | expectedId
    "0"                                             | zero()
    "1"                                             | one()
    "18446744073709551615"                          | max()
    "${Long.MAX_VALUE}"                             | from(Long.MAX_VALUE)
    "${BigInteger.valueOf(Long.MAX_VALUE).plus(1)}" | from(Long.MIN_VALUE)
  }

  def "fail on illegal String"() {
    when:
    from(stringId)

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
    final ddid = fromHex(hexId)
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
    "0"                      | zero()
    "1"                      | one()
    "f" * 16                 | max()
    "7" + "f" * 15           | from(Long.MAX_VALUE)
    "8" + "0" * 15           | from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15 | from(Long.MIN_VALUE)
    "cafebabe"               | from(3405691582)
    "123456789abcdef"        | from(81985529216486895)
  }

  def "fail on illegal hex String"() {
    when:
    fromHex(hexId)

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
    final ddid = generate((IdGenerationStrategy) idGenerator)

    then:
    !ddid.equals(null)
    !ddid.equals("foo")
    ddid != zero()
    ddid.equals(ddid)
    ddid.hashCode() == (int) (ddid.toLong() ^ (ddid.toLong() >>> 32))

    where:
    idGenerator << IdGenerationStrategy.values()
  }
}

class DDSpanIdTest extends DDIdTest<DDSpanId> {
  @Override
  DDSpanId zero() {
    return DDSpanId.ZERO
  }

  @Override
  DDSpanId one() {
    return DDSpanId.ONE
  }

  @Override
  DDSpanId max() {
    return DDSpanId.MAX
  }

  @Override
  DDSpanId from(long id) {
    return DDSpanId.from(id)
  }

  @Override
  DDSpanId from(String id) {
    return DDSpanId.from(id)
  }

  @Override
  DDSpanId fromHex(String id) {
    return DDSpanId.fromHex(id)
  }

  @Override
  DDSpanId generate(IdGenerationStrategy strategy) {
    return strategy.generateSpanId()
  }

  def "convert ids from/to hex String while keeping the original"() {
    when:
    final ddid = DDSpanId.fromHexWithOriginal(hexId)

    then:
    ddid == expectedId
    ddid.toHexStringOrOriginal() == hexId

    where:
    hexId                    | expectedId
    "0"                      | zero()
    "1"                      | one()
    "f" * 16                 | max()
    "7" + "f" * 15           | from(Long.MAX_VALUE)
    "8" + "0" * 15           | from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15 | from(Long.MIN_VALUE)
    "cafebabe"               | from(3405691582)
    "123456789abcdef"        | from(81985529216486895)
  }
}

class DDTraceIdTest extends DDIdTest<DDTraceId> {
  @Override
  DDTraceId zero() {
    return DDTraceId.ZERO
  }

  @Override
  DDTraceId one() {
    return DDTraceId.ONE
  }

  @Override
  DDTraceId max() {
    return DDTraceId.MAX
  }

  @Override
  DDTraceId from(long id) {
    return DDTraceId.from(id)
  }

  @Override
  DDTraceId from(String id) {
    return DDTraceId.from(id)
  }

  @Override
  DDTraceId fromHex(String id) {
    return DDTraceId.fromHex(id)
  }

  @Override
  DDTraceId generate(IdGenerationStrategy strategy) {
    return strategy.generateTraceId()
  }

  def "convert ids from/to hex String and truncate to 64 bits while keeping the original"() {
    when:
    final ddid = DDTraceId.fromHexTruncatedWithOriginal(hexId)

    then:
    ddid == expectedId
    ddid.toHexStringOrOriginal() == hexId

    where:
    hexId                          | expectedId
    "000"                          | zero()
    "0001"                         | one()
    "f" * 16                       | max()
    "7" + "f" * 15                 | from(Long.MAX_VALUE)
    "8" + "0" * 15                 | from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15       | from(Long.MIN_VALUE)
    "1" * 8 + "0" * 8 + "cafebabe" | from(3405691582)
    "1" * 12 + "0123456789abcdef"  | from(81985529216486895)
  }
}
