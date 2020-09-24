package datadog.trace.core.util

import datadog.trace.util.test.DDSpecification

import java.util.concurrent.TimeUnit

class SimpleRateLimiterTest extends DDSpecification {
  def "initial rate available at creation"() {
    setup:
    def limiter = new SimpleRateLimiter(rate, TimeUnit.SECONDS, new SettableClock())

    when:
    rate.times {
      assert limiter.tryAcquire(): "failed for $it"
    }

    then:
    assert !limiter.tryAcquire()

    where:
    rate << [10, 100, 1000]
  }

  def "tokens never go beyond rate"() {
    setup:
    def clock = new SettableClock()
    def limiter = new SimpleRateLimiter(rate, TimeUnit.SECONDS, clock)

    when:
    clock.time += TimeUnit.SECONDS.toNanos(5)
    rate.times {
      assert limiter.tryAcquire(): "failed for $it"
    }

    then:
    assert !limiter.tryAcquire()

    where:
    rate << [10, 100, 1000]
  }

  def "tokens refill at unit / rate"() {
    setup:
    def clock = new SettableClock()
    def limiter = new SimpleRateLimiter(rate, TimeUnit.SECONDS, clock)

    when:
    rate.times {
      assert limiter.tryAcquire(): "failed for $it"
    }

    then:
    assert !limiter.tryAcquire()

    when:
    clock.time += TimeUnit.SECONDS.toNanos(1) / rate

    then:
    assert limiter.tryAcquire()
    assert !limiter.tryAcquire()

    when:
    clock.time += TimeUnit.SECONDS.toNanos(1) / rate
    clock.time += TimeUnit.SECONDS.toNanos(1) / rate

    then:
    assert limiter.tryAcquire()
    assert limiter.tryAcquire()
    assert !limiter.tryAcquire()

    where:
    rate << [10, 100, 100]
  }

  def "partial intervals handled"() {
    setup:
    def clock = new SettableClock()
    def limiter = new SimpleRateLimiter(rate, TimeUnit.SECONDS, clock)

    when:
    rate.times {
      assert limiter.tryAcquire(): "failed for $it"
    }

    then:
    assert !limiter.tryAcquire()

    when: "Add an interval and a half"
    clock.time += TimeUnit.SECONDS.toNanos(1) / rate
    clock.time += TimeUnit.SECONDS.toNanos(1) / (2 * rate)

    then: "Only one token available"
    assert limiter.tryAcquire()
    assert !limiter.tryAcquire()

    when: "Add an interval and a half again"
    clock.time += TimeUnit.SECONDS.toNanos(1) / rate
    clock.time += TimeUnit.SECONDS.toNanos(1) / (2 * rate)

    then: "Two tokens available"
    assert limiter.tryAcquire()
    assert limiter.tryAcquire()
    assert !limiter.tryAcquire()

    where:
    rate << [10, 100, 100]
  }
}

class SettableClock implements SimpleRateLimiter.TimeSource {
  long time = 0

  @Override
  long getTime() {
    return time
  }
}
