package datadog.trace.relocate.api

import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.test.util.DDSpecification
import org.slf4j.Logger

import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.MINUTES
import static java.util.concurrent.TimeUnit.NANOSECONDS

class RateLimitedLoggerTest extends DDSpecification {
  final exception = new RuntimeException("bad thing")

  def "Debug level"() {
    setup:
    Logger log = Mock(Logger)
    ControllableTimeSource timeSource = new ControllableTimeSource()
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
    1 * log.warn("test {} {} (Will not log warnings for 5 minutes)", "message", exception)
    firstLog
    !secondLog
  }


  def "warning once"() {
    setup:
    Logger log = Mock(Logger)
    ControllableTimeSource timeSource = new ControllableTimeSource()
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 1, MINUTES, timeSource)
    log.isWarnEnabled() >> true
    log.isDebugEnabled() >> false

    when:
    def firstLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    1 * log.warn("test {} {} (Will not log warnings for 1 minute)", "message", exception)
    firstLog

    when:
    def secondLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    !secondLog
  }

  def "warning once negative time"() {
    setup:
    Logger log = Mock(Logger)

    ControllableTimeSource timeSource = new ControllableTimeSource()
    timeSource.set(Long.MIN_VALUE)
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 5, NANOSECONDS, timeSource)
    log.isWarnEnabled() >> true
    log.isDebugEnabled() >> false

    when:
    def firstLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    1 * log.warn("test {} {} (Will not log warnings for 5 nanoseconds)", "message", exception)
    firstLog

    when:
    timeSource.advance(5 - 1)
    def secondLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    !secondLog
  }

  def "warning once -zero- time"() {
    setup:
    Logger log = Mock(Logger)

    ControllableTimeSource timeSource = new ControllableTimeSource()
    timeSource.set(0)
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 5, NANOSECONDS, timeSource)
    log.isWarnEnabled() >> true
    log.isDebugEnabled() >> false

    when:
    def firstLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    1 * log.warn("test {} {} (Will not log warnings for 5 nanoseconds)", "message", exception)
    firstLog

    when:
    timeSource.advance(1)
    def secondLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    !secondLog
  }

  def "warning twice"() {
    setup:
    Logger log = Mock(Logger)
    ControllableTimeSource timeSource = new ControllableTimeSource()
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 7, NANOSECONDS, timeSource)
    log.isWarnEnabled() >> true
    log.isDebugEnabled() >> false

    when:
    def firstLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    1 * log.warn("test {} {} (Will not log warnings for 7 nanoseconds)", "message", exception)
    firstLog

    when:
    timeSource.advance(7)
    def secondLog = rateLimitedLog.warn("test {} {}", "message", exception)

    then:
    1 * log.warn("test {} {} (Will not log warnings for 7 nanoseconds)", "message", exception)
    secondLog
  }

  def "no args"() {
    setup:
    Logger log = Mock(Logger)
    ControllableTimeSource timeSource = new ControllableTimeSource()
    RatelimitedLogger rateLimitedLog = new RatelimitedLogger(log, 1, MILLISECONDS, timeSource)
    log.isWarnEnabled() >> true
    log.isDebugEnabled() >> false

    when:
    rateLimitedLog.warn("test")

    then:
    1 * log.warn("test (Will not log warnings for 1 millisecond)")
  }
}
