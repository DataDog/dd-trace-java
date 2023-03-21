package datadog.trace.api

import datadog.trace.test.util.DDSpecification

import java.security.SecureRandom

import static datadog.trace.api.IdGenerationStrategy.Trace128bitStrategy.GENERATION
import static datadog.trace.api.IdGenerationStrategy.Trace128bitStrategy.GENERATION_AND_LOG_INJECTION
import static datadog.trace.api.IdGenerationStrategy.Trace128bitStrategy.UNSUPPORTED

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
      assert traceId.hashCode() == (int) (traceId.toLong() ^ (traceId.toLong() >>> 32))
      assert !checked.contains(traceId)
      checked.add(traceId)
    }

    where:
    tId128b | strategyName
    UNSUPPORTED   | "RANDOM"
    UNSUPPORTED   | "SEQUENTIAL"
    UNSUPPORTED   | "SECURE_RANDOM"
    GENERATION    | "RANDOM"
    GENERATION    | "SEQUENTIAL"
    GENERATION    | "SECURE_RANDOM"

    tIdSize = GENERATION ? 128 : 64
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

  def "check trace 128-bit strategy choices"() {
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
