package datadog.trace.logging

import spock.lang.Specification

class LogLevelTest extends Specification {

  def "test fromString"() {
    when:
    LogLevel fromString = LogLevel.fromString(str)

    then:
    fromString == level

    where:
    str     | level
    "trace" | LogLevel.TRACE
    "debug" | LogLevel.DEBUG
    "info"  | LogLevel.INFO
    "warn"  | LogLevel.WARN
    "error" | LogLevel.ERROR
    "off"   | LogLevel.OFF
    "foo"   | LogLevel.INFO
  }

  def "test isEnabled"() {
    expect:
    LogLevel.TRACE.isEnabled(level) == trace
    LogLevel.DEBUG.isEnabled(level) == debug
    LogLevel.INFO.isEnabled(level) == info
    LogLevel.WARN.isEnabled(level) == warn
    LogLevel.ERROR.isEnabled(level) == error
    LogLevel.OFF.isEnabled(level) == off

    where:
    level          | trace | debug | info  | warn  | error | off
    LogLevel.TRACE | true  | true  | true  | true  | true  | true
    LogLevel.DEBUG | false | true  | true  | true  | true  | true
    LogLevel.INFO  | false | false | true  | true  | true  | true
    LogLevel.WARN  | false | false | false | true  | true  | true
    LogLevel.ERROR | false | false | false | false | true  | true
    LogLevel.OFF   | false | false | false | false | false | true
  }
}
