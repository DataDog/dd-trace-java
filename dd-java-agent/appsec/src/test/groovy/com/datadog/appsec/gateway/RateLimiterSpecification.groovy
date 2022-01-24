package com.datadog.appsec.gateway

import spock.lang.Specification

class RateLimiterSpecification extends Specification {

  interface NanoProvider {
    long getNanoTime()
  }

  static class TestingRateLimiter extends RateLimiter {
    final mock
    TestingRateLimiter(int limitPerSec, cb, mock) {
      super(limitPerSec, cb)
      this.mock = mock
    }

    @Override
    protected long getNanoTime() {
      mock.nanoTime
    }
  }

  int throttledCounter = 0
  def mock = Mock(NanoProvider)
  RateLimiter testee = new TestingRateLimiter(
  10, { throttledCounter++ } as RateLimiter.ThrottledCallback, mock)

  void 'limit is respected in a single interval'() {
    setup:
    def count = 0

    when:
    15.times {testee.throttled || count++ }

    then:
    15 * mock.nanoTime >> System.nanoTime()
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
    8 * mock.nanoTime >> initialTime
    count == 8

    when:
    20.times {testee.throttled || count++ }

    then:
    20 * mock.nanoTime >> initialTime + 1_500_000_000L
    // 4 events from previous period are considered.
    count == 8 + (10 - 4)
  }


  void 'limit considers fraction of previous interval — ushort wrap around'() {
    setup:
    def initialTime = 1_000_000_000L
    def count = 0

    // this is needed because of initial state of the rate limiter:
    when:
    testee.throttled
    then:
    1 * mock.nanoTime >> -42_000_000_000L

    when:
    8.times {testee.throttled || count++ }

    then:
    8 * mock.nanoTime >> initialTime
    count == 8

    when:
    20.times {testee.throttled || count++ }

    then:
    20 * mock.nanoTime >> initialTime + 1_500_000_000L
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
    8 * mock.nanoTime >> initialTime
    count == 8

    when:
    20.times {testee.throttled || count++ }

    then:
    20 * mock.nanoTime >> initialTime + 2_000_000_000L
    count == 8 + 10

    when:
    20.times {testee.throttled || count++ }

    then:
    20 * mock.nanoTime >> initialTime
    count == 8 + 10 + 10
  }

  void 'if 1 sec behind consider it is in the same second'() {
    setup:
    def initialTime = 1_000_000_000L
    def count = 0

    when:
    8.times {testee.throttled || count++ }

    then:
    8 * mock.nanoTime >> initialTime

    when:
    20.times {testee.throttled || count++ }

    then:
    20 * mock.nanoTime >> initialTime - 1_000_000_000L
    count == 10
  }

  void 'if 1 sec behind consider it is in the same second — ushort wrap variant'() {
    setup:
    def initialTime = 0
    def count = 0

    when:
    8.times {testee.throttled || count++ }

    then:
    8 * mock.nanoTime >> initialTime

    when:
    20.times {testee.throttled || count++ }

    then:
    20 * mock.nanoTime >> initialTime - 1_000_000_000L
    count == 10
  }
}
