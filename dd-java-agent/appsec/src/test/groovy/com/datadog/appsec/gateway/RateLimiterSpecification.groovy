package com.datadog.appsec.gateway

import datadog.trace.api.time.TimeSource
import spock.lang.Specification

class RateLimiterSpecification extends Specification {
  int throttledCounter = 0
  def mock = Mock(TimeSource)
  RateLimiter testee = new RateLimiter(
  10, mock, { throttledCounter++ } as RateLimiter.ThrottledCallback)

  void 'limit is respected in a single interval'() {
    setup:
    def count = 0

    when:
    15.times {testee.throttled || count++ }

    then:
    15 * mock.nanoTicks >> System.nanoTime()
    count == 10
    throttledCounter == 5
  }

  void 'limit considers fraction of previous interval'() {
    setup:
    def initialTime = 0
    def count = 0

    when:
    8.times {testee.throttled || count++ }

    then:
    8 * mock.nanoTicks >> initialTime
    count == 8

    when:
    20.times {testee.throttled || count++ }

    then:
    20 * mock.nanoTicks >> initialTime + 1_500_000_000L
    // 4 events from previous period are considered.
    count == 8 + (10 - 4)
  }

  void 'limit may be applied even when 1 sec ahead'() {
    setup:
    def initialTime = 0
    def count = 0

    when:
    10.times {testee.throttled || count++ }

    then:
    10 * mock.nanoTicks >> initialTime
    count == 10

    when:
    testee.throttled || count++

    then:
    1 * mock.nanoTicks >> initialTime + 1_000_000_000L
    count == 10
  }

  void 'limit considers fraction of previous interval — ushort wrap around'() {
    setup:
    def initialTime = 1_000_000_000L
    def count = 0

    // this is needed because of initial state of the rate limiter:
    when:
    testee.throttled
    then:
    1 * mock.nanoTicks >> -42_000_000_000L

    when:
    8.times {testee.throttled || count++ }

    then:
    8 * mock.nanoTicks >> initialTime
    count == 8

    when:
    20.times {testee.throttled || count++ }

    then:
    20 * mock.nanoTicks >> initialTime + 1_500_000_000L
    // 4 events from previous period are considered.
    count == 8 + (10 - 4)
  }

  void 'limit ignores fraction of old interval'() {
    setup:
    def initialTime = 0
    def count = 0

    when:
    8.times {testee.throttled || count++ }

    then:
    8 * mock.nanoTicks >> initialTime
    count == 8

    when:
    20.times {testee.throttled || count++ }

    then:
    20 * mock.nanoTicks >> initialTime + 2_000_000_000L
    count == 8 + 10

    when:
    20.times {testee.throttled || count++ }

    then:
    20 * mock.nanoTicks >> initialTime
    count == 8 + 10 + 10
  }

  void 'if 1 sec behind reread the time with initialTime=#initialTime'() {
    setup:
    def count = 0

    when:
    9.times {testee.throttled || count++ }

    then:
    9 * mock.nanoTicks >> initialTime

    when:
    testee.throttled || count++

    then:
    2 * mock.nanoTicks >>> [initialTime - 1_000_000_000L, initialTime]
    count == 10

    when:
    testee.throttled || count++

    then:
    2 * mock.nanoTicks >>> [initialTime - 1_000_000_000L, initialTime]
    count == 10

    where:
    initialTime << [1_000_000_000L, 0L]
  }

  void 'if still 1 sec behind after reread assume the time has wrapped around'() {
    setup:
    def initialTime = 1_000_000_000L
    def count = 0

    when:
    10.times {testee.throttled || count++ }

    then:
    10 * mock.nanoTicks >> initialTime

    when:
    testee.throttled || count++

    then:
    2 * mock.nanoTicks >> initialTime - 1_000_000_000L
    count == 11
  }
}
