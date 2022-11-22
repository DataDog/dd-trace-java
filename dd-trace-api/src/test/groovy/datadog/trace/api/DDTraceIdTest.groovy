package datadog.trace.api

import datadog.trace.test.util.DDSpecification

import java.security.SecureRandom

class DDTraceIdTest extends DDSpecification {

  def "convert ids from/to long and check strings"() {
    when:
    final ddid = DDTraceId.from(longId)

    then:
    ddid == expectedId
    ddid.toLong() == longId
    ddid.toString() == expectedString
    ddid.toHexString() == expectedHex
    ddid.toHexStringOrOriginal() == expectedHex

    where:
    longId         | expectedId                     | expectedString         | expectedHex
    0              | DDTraceId.ZERO                 | "0"                    | "0"
    1              | DDTraceId.ONE                  | "1"                    | "1"
    -1             | DDTraceId.MAX                  | "18446744073709551615" | "f" * 16
    Long.MAX_VALUE | DDTraceId.from(Long.MAX_VALUE) | "9223372036854775807"  | "7" + "f" * 15
    Long.MIN_VALUE | DDTraceId.from(Long.MIN_VALUE) | "9223372036854775808"  | "8" + "0" * 15
  }

  def "convert ids from/to String"() {
    when:
    final ddid = DDTraceId.from(stringId)

    then:
    ddid == expectedId
    ddid.toString() == stringId

    where:
    stringId                                        | expectedId
    "0"                                             | DDTraceId.ZERO
    "1"                                             | DDTraceId.ONE
    "18446744073709551615"                          | DDTraceId.MAX
    "${Long.MAX_VALUE}"                             | DDTraceId.from(Long.MAX_VALUE)
    "${BigInteger.valueOf(Long.MAX_VALUE).plus(1)}" | DDTraceId.from(Long.MIN_VALUE)
  }

  def "fail on illegal String"() {
    when:
    DDTraceId.from(stringId)

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
    final ddid = DDTraceId.fromHex(hexId)
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
    "0"                      | DDTraceId.ZERO
    "1"                      | DDTraceId.ONE
    "f" * 16                 | DDTraceId.MAX
    "7" + "f" * 15           | DDTraceId.from(Long.MAX_VALUE)
    "8" + "0" * 15           | DDTraceId.from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15 | DDTraceId.from(Long.MIN_VALUE)
    "cafebabe"               | DDTraceId.from(3405691582)
    "123456789abcdef"        | DDTraceId.from(81985529216486895)
  }

  def "fail on illegal hex String"() {
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

  def "generate id with #strategyName"() {
    when:
    def strategy = IdGenerationStrategy.fromName(strategyName)
    def traceIds = (0..32768).collect { strategy.generateTraceId() }
    Set<DDTraceId> checked = new HashSet<>()

    then:
    traceIds.forEach { traceId ->
      assert !traceId.equals(null)
      assert !traceId.equals("foo")
      assert traceId != DDTraceId.ZERO
      assert traceId.equals(traceId)
      assert traceId.hashCode() == (int) (traceId.toLong() ^ (traceId.toLong() >>> 32))
      assert !checked.contains(traceId)
      checked.add(traceId)
    }

    where:
    strategyName << ["RANDOM", "SEQUENTIAL", "SECURE_RANDOM"]
  }

  def "return null for non existing strategy #strategyName"() {
    when:
    def strategy = IdGenerationStrategy.fromName(strategyName)

    then:
    strategy == null

    where:
    // Check unknown strategies for code coverage
    strategyName << ["SOME", "UNKNOWN", "STRATEGIES"]
  }

  def "convert ids from/to hex String and truncate to 64 bits while keeping the original"() {
    when:
    final ddid = DDTraceId.fromHexTruncatedWithOriginal(hexId)

    then:
    ddid == expectedId
    ddid.toHexStringOrOriginal() == hexId

    where:
    hexId                          | expectedId
    "000"                          | DDTraceId.ZERO
    "0001"                         | DDTraceId.ONE
    "f" * 16                       | DDTraceId.MAX
    "7" + "f" * 15                 | DDTraceId.from(Long.MAX_VALUE)
    "8" + "0" * 15                 | DDTraceId.from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15       | DDTraceId.from(Long.MIN_VALUE)
    "1" * 8 + "0" * 8 + "cafebabe" | DDTraceId.from(3405691582)
    "1" * 12 + "0123456789abcdef"  | DDTraceId.from(81985529216486895)
  }

  def "exception created on SecureRandom strategy"() {
    setup:
    def provider = Mock(IdGenerationStrategy.ThrowingSupplier)

    when:
    new IdGenerationStrategy.SRandom(provider)

    then:
    1 * provider.get() >> { throw new IllegalArgumentException("SecureRandom init exception") }
    0 * _
    final ExceptionInInitializerError exception = thrown()
    exception.cause.message == "SecureRandom init exception"
  }

  def "SecureRandom ids will always be non-zero"() {
    setup:
    def provider = Mock(IdGenerationStrategy.ThrowingSupplier)
    def random = Mock(SecureRandom)

    when:
    def strategy = new IdGenerationStrategy.SRandom(provider)
    strategy.generateTraceId().toLong() == 47
    strategy.generateSpanId() == 11

    then:
    1 * provider.get() >> { random }
    1 * random.nextLong() >> { 0 }
    1 * random.nextLong() >> { 47 }
    1 * random.nextLong() >> { 0 }
    1 * random.nextLong() >> { 11 }
    0 * _
  }
}
