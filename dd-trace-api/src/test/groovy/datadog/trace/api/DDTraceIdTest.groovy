package datadog.trace.api

import datadog.trace.test.util.DDSpecification

import java.security.SecureRandom

import static datadog.trace.api.IdGenerationStrategy.Trace128bitStrategy.GENERATION
import static datadog.trace.api.IdGenerationStrategy.Trace128bitStrategy.GENERATION_AND_LOG_INJECTION
import static datadog.trace.api.IdGenerationStrategy.Trace128bitStrategy.UNSUPPORTED

class DDTraceIdTest extends DDSpecification {

  def "convert 64-bit ids from/to long and check strings"() {
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

  def "convert 64-bit ids from/to String"() {
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

  def "convert 128-bit ids from/to String"() {
    when:
    def parsedId = DDTraceId.from(stringId)
    def id = DDTraceId.create(high, low, null)

    then:
    id == parsedId
    parsedId.toString() == stringId
    id.toString() == stringId

    where:
    high           | low            | stringId
    Long.MIN_VALUE | Long.MIN_VALUE | "80000000000000008000000000000000"
    Long.MIN_VALUE | 1L             | "80000000000000000000000000000001"
    Long.MIN_VALUE | Long.MAX_VALUE | "80000000000000007fffffffffffffff"
    1L             | Long.MIN_VALUE | "00000000000000018000000000000000"
    1L             | 1L             | "00000000000000010000000000000001"
    1L             | Long.MAX_VALUE | "00000000000000017fffffffffffffff"
    Long.MAX_VALUE | Long.MIN_VALUE | "7fffffffffffffff8000000000000000"
    Long.MAX_VALUE | 1L             | "7fffffffffffffff0000000000000001"
    Long.MAX_VALUE | Long.MAX_VALUE | "7fffffffffffffff7fffffffffffffff"
  }

  def "fail on illegal String"() {
    when:
    DDTraceId.from(stringId)

    then:
    thrown IllegalArgumentException

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
    def strategy = IdGenerationStrategy.fromName(strategyName, trace128bitStrategy)
    def traceIds = (0..32768).collect { strategy.generateTraceId() }
    Set<DDTraceId> checked = new HashSet<>()

    then:
    traceIds.forEach { traceId ->
      assert !traceId.equals(null)
      assert !traceId.equals("foo")
      assert traceId != DDTraceId.ZERO
      assert traceId.equals(traceId)
      assert traceId.hashCode() == (int) (traceId.toLong() ^ (traceId.toLong() >>> 32))
      assert checked.add(traceId)
    }

    where:
    strategyName    | trace128bitStrategy
    "RANDOM"        | UNSUPPORTED
    "RANDOM"        | GENERATION
    "SEQUENTIAL"    | UNSUPPORTED
    "SECURE_RANDOM" | UNSUPPORTED
    "SECURE_RANDOM" | GENERATION
  }

  def "return null for non existing strategy #strategyName"() {
    when:
    def strategy = IdGenerationStrategy.fromName(strategyName, UNSUPPORTED)

    then:
    strategy == null

    where:
    // Check unknown strategies for code coverage
    strategyName << ["SOME", "UNKNOWN", "STRATEGIES"]
  }

  def "convert ids from/to hex String and truncate to 64 bits while keeping the original"() {
    when:
    DDTraceId ddid = null
    try {
      ddid = DDTraceId.fromHexTruncatedWithOriginal(hexId)
    } catch (NumberFormatException ignored) {
    }

    then:
    if (expectedId) {
      assert ddid == expectedId
      assert ddid.toHexStringOrOriginal() == hexId
    } else {
      assert !ddid
    }

    where:
    hexId                          | expectedId
    null                           | null
    "000"                          | DDTraceId.ZERO
    "0001"                         | DDTraceId.ONE
    "f" * 16                       | DDTraceId.MAX
    "7" + "f" * 15                 | DDTraceId.from(Long.MAX_VALUE)
    "8" + "0" * 15                 | DDTraceId.from(Long.MIN_VALUE)
    "0" * 4 + "8" + "0" * 15       | DDTraceId.from(Long.MIN_VALUE)
    "1" * 8 + "0" * 8 + "cafebabe" | DDTraceId.from(3405691582)
    "1" * 12 + "0123456789abcdef"  | DDTraceId.from(81985529216486895)
  }

  def "convert ids from/to part of hex String and truncate to 64 bits while keeping the original part"() {
    when:
    DDTraceId ddid = null
    try {
      ddid = DDTraceId.fromHexTruncatedWithOriginal(hexId, start, length, lcOnly)
    } catch (NumberFormatException ignored) {
    }

    then:
    if (expectedId) {
      assert ddid == expectedId
      assert ddid.toHexStringOrOriginal() == expectedHex
    } else {
      assert !ddid
    }

    where:
    hexId                          | start| length | lcOnly | expectedHex | expectedId
    null                           |  1   |  1     | false  | null        | null
    ""                             |  1   |  1     | false  | null        | null
    "00"                           | -1   |  1     | false  | null        | null
    "00"                           |  0   |  0     | false  | null        | null
    "00"                           |  1   |  1     | false  | "0"         | DDTraceId.ZERO
    "0001"                         |  2   |  2     | false  | "01"        | DDTraceId.ONE
    "f" * 16                       |  0   |  16    | true   | "f" * 16    | DDTraceId.MAX
    "f" * 12 + "Ffff"              |  0   |  16    | true   | null        | null
    "fFff" + ("f" * 16)            |  0   |  20    | true   | null        | null
    "Cafe" + ("f" * 16) + "F00d"   |  4   |  16    | false  | "f" * 16    | DDTraceId.MAX
  }

  def "exception created on SecureRandom strategy"() {
    setup:
    def provider = Mock(IdGenerationStrategy.ThrowingSupplier)

    when:
    new IdGenerationStrategy.SRandom(UNSUPPORTED, provider)

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
    def strategy = new IdGenerationStrategy.SRandom(UNSUPPORTED, provider)
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

  def "SecureRandom128 ids can only be zero for span id"() {
    setup:
    def provider = Mock(IdGenerationStrategy.ThrowingSupplier)
    def random = Mock(SecureRandom)

    when:
    def strategy = new IdGenerationStrategy.SRandom(GENERATION, provider)
    strategy.generateTraceId().toLong() == 0
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

  def "trace 128-bit strategy choices"() {
    when:
    def strategy = IdGenerationStrategy.Trace128bitStrategy.get(withGeneration, withLogInjection)

    then:
    strategy == expected

    where:
    withGeneration | withLogInjection | expected
    false          | false            | UNSUPPORTED
    false          | true             | UNSUPPORTED
    true           | false            | GENERATION
    true           | true             | GENERATION_AND_LOG_INJECTION
  }
}
