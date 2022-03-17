package datadog.trace.relocate.api

import datadog.trace.api.time.TimeSource
import datadog.trace.test.util.DDSpecification
import org.slf4j.Logger

import java.util.concurrent.atomic.AtomicInteger

import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.MINUTES
import static java.util.concurrent.TimeUnit.NANOSECONDS

class RateLimitedLoggerTest extends DDSpecification {
  final exception = new RuntimeException("bad thing")

  def "Debug level"() {
    setup:
    Logger log = Mock(Logger)
    TimeSource timeSource = Mock(TimeSource)
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 5, MINUTES, timeSource)
    log.isDebugEnabled() >> true

    when:
    rateLimitedLog.warn("test {} {}", "message", exception)
    rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    2 * log.warn("test {} {}", "message", exception)
  }

  def "default warning once"() {
    setup:
    Logger log = Mock(Logger)
    def defaultRateLimitedLog = new RatelimitedLogger(log, 5, MINUTES)
    log.isWarnEnabled() >> true
    log.isDebugEnabled() >> false

    when:
    def firstLog = defaultRateLimitedLog.warn("test {} {}", "message", exception)
    def secondLog = defaultRateLimitedLog.warn("test {} {}", "message", exception)

    then:
    1 * log.warn("test {} {} (Will not log errors for 5 minutes)", "message", exception)
    firstLog
    !secondLog
  }


  def "warning once"() {
    setup:
    Logger log = Mock(Logger)
    TimeSource timeSource = Mock(TimeSource)
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 1, MINUTES, timeSource)
    log.isWarnEnabled() >> true
    log.isDebugEnabled() >> false
    timeSource.getNanoTime() >> MINUTES.toNanos(1)

    when:
    def firstLog = rateLimitedLog.warn("test {} {}", "message", exception)
    def secondLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    1 * log.warn("test {} {} (Will not log errors for 1 minute)", "message", exception)
    firstLog
    !secondLog
  }


  def "warning once negative time"() {
    setup:
    AtomicInteger counter = new AtomicInteger(0)
    Logger log = Mock(Logger)
    TimeSource timeSource = Mock(TimeSource)
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 5, NANOSECONDS, timeSource)
    log.isWarnEnabled() >> true
    log.isDebugEnabled() >> false
    timeSource.getNanoTime() >> {
      int invocation = counter.getAndIncrement()
      if (invocation == 0) {
        return Long.MIN_VALUE
      }
      return Long.MIN_VALUE + 5 - 1
    }

    when:
    def firstLog = rateLimitedLog.warn("test {} {}", "message", exception)
    def secondLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    counter.get() == 2
    1 * log.warn("test {} {} (Will not log errors for 5 nanoseconds)", "message", exception)
    firstLog
    !secondLog
  }

  def "warning twice"() {
    setup:
    Logger log = Mock(Logger)
    TimeSource timeSource = Mock(TimeSource)
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 7, NANOSECONDS, timeSource)
    log.isWarnEnabled() >> true
    log.isDebugEnabled() >> false
    timeSource.getNanoTime() >>> [7, 7 * 2]

    when:
    def firstLog = rateLimitedLog.warn("test {} {}", "message", exception)
    def secondLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    2 * log.warn("test {} {} (Will not log errors for 7 nanoseconds)", "message", exception)
    firstLog
    secondLog
  }

  def "no logs"() {
    setup:
    Logger log = Mock(Logger)
    TimeSource timeSource = Mock(TimeSource)
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 5, MINUTES, timeSource)

    when:
    rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    0 * log.warn(_, _)
  }

  def "no args"() {
    setup:
    Logger log = Mock(Logger)
    TimeSource timeSource = Mock(TimeSource)
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 1, MILLISECONDS, timeSource)
    log.isWarnEnabled() >> true
    log.isDebugEnabled() >> false
    timeSource.getNanoTime() >> MILLISECONDS.toNanos(1)

    when:
    rateLimitedLog.warn("test")

    then:
    1 * log.warn("test (Will not log errors for 1 millisecond)")
  }
}
