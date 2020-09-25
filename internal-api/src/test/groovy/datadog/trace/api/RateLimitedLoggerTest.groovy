package datadog.trace.api

import datadog.trace.api.time.TimeSource
import datadog.trace.util.test.DDSpecification
import org.slf4j.Logger

import static java.util.concurrent.TimeUnit.MINUTES

class RateLimitedLoggerTest extends DDSpecification {
  final delay = 5
  final exception = new RuntimeException("bad thing")

  Logger log = Mock(Logger)
  TimeSource timeSource = Mock(TimeSource)
  RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, delay, timeSource)

  def "Debug level"() {
    setup:
    log.isDebugEnabled() >> true

    when:
    rateLimitedLog.warn("test {} {}", "message", exception)
    rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    2 * log.warn("test {} {}", "message", exception)
  }

  def "default warning once"() {
    setup:
    def defaultRateLimitedLog = new RatelimitedLogger(log, MINUTES.toNanos(5))
    log.isWarnEnabled() >> true

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
    log.isWarnEnabled() >> true
    timeSource.get() >> delay

    when:
    def firstLog = rateLimitedLog.warn("test {} {}", "message", exception)
    def secondLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    1 * log.warn("test {} {} (Will not log errors for 5 minutes)", "message", exception)
    firstLog
    !secondLog
  }

  def "warning twice"() {
    setup:
    log.isWarnEnabled() >> true
    timeSource.get() >>> [delay, delay * 2]

    when:
    def firstLog = rateLimitedLog.warn("test {} {}", "message", exception)
    def secondLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    2 * log.warn("test {} {} (Will not log errors for 5 minutes)", "message", exception)
    firstLog
    secondLog
  }

  def "no logs"() {
    when:
    rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    0 * log.warn(_, _)
  }

  def "no args"() {
    setup:
    log.isWarnEnabled() >> true
    timeSource.get() >> delay

    when:
    rateLimitedLog.warn("test")

    then:
    1 * log.warn("test (Will not log errors for 5 minutes)")
  }
}
