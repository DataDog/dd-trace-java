package datadog.metrics.api

import datadog.metrics.impl.MonitoringImpl
import datadog.metrics.statsd.StatsDClient
import spock.lang.Specification

import static java.util.concurrent.TimeUnit.MILLISECONDS

class CounterTest extends Specification {

  def "counter counts stuff"() {
    StatsDClient statsd = Mock(StatsDClient)
    Monitoring monitoring = new MonitoringImpl(statsd, 100, MILLISECONDS)
    def counter = monitoring.newCounter("my_counter")
    when:
    counter.increment(1)
    then:
    1 * statsd.count("my_counter", 1, [])
    0 * _
  }

  def "counter tags error counts with cause"() {
    StatsDClient statsd = Mock(StatsDClient)
    Monitoring monitoring = new MonitoringImpl(statsd, 100, MILLISECONDS)
    def counter = monitoring.newCounter("my_counter")
    when:
    counter.incrementErrorCount("bad stuff happened", 1000)
    then:
    1 * statsd.count("my_counter", 1000, ["cause:bad_stuff_happened"])
    0 * _
  }

  def "disabled monitoring produces no op counters"() {
    setup:
    Monitoring monitoring = Monitoring.DISABLED
    when:
    Counter counter = monitoring.newCounter("foo")
    then:
    counter instanceof NoOpCounter
  }

  def "no-op counters are safe"() {
    setup:
    Counter counter = Monitoring.DISABLED.newCounter("foo")
    expect:
    try {
      counter.increment(1)
      counter.incrementErrorCount("cause", 1)
    } catch (Throwable t) {
      Assertions.fail(t.getMessage())
    }
  }
}
