package datadog.trace.api

import datadog.trace.test.util.DDSpecification

import java.security.SecureRandom

class IdGenerationStrategyTest extends DDSpecification {
  def "generate id with #strategyName and #tIdSize bits"() {
    when:
    def strategy = IdGenerationStrategy.fromName(strategyName, tId128b)
    def traceIds = (0..32768).collect { strategy.generateTraceId() }
    Set<DDTraceId> checked = new HashSet<>()

    then:
    traceIds.forEach { traceId ->
      // Test equals implementation
      assert !traceId.equals(null)
      assert !traceId.equals("foo")
      assert traceId != DDTraceId.ZERO
      assert traceId.equals(traceId)
      // Test #hashCode implementation
      assert traceId.hashCode() == (int) (traceId.toHighOrderLong() ^ (traceId.toHighOrderLong() >>> 32) ^ traceId.toLong() ^ (traceId.toLong() >>> 32))
      assert !checked.contains(traceId)
      checked.add(traceId)
    }

    where:
    tId128b | strategyName
    false   | "RANDOM"
    false   | "SEQUENTIAL"
    false   | "SECURE_RANDOM"
    true    | "RANDOM"
    true    | "SEQUENTIAL"
    true    | "SECURE_RANDOM"

    tIdSize = tId128b ? 128 : 64
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

  def "exception created on SecureRandom strategy"() {
    setup:
    def provider = Mock(IdGenerationStrategy.ThrowingSupplier)

    when:
    new IdGenerationStrategy.SRandom(false, provider)

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
    def strategy = new IdGenerationStrategy.SRandom(false, provider)
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
