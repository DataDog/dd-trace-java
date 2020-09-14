package datadog.trace.core.monitor

import com.timgroup.statsd.StatsDClient
import datadog.trace.util.test.DDSpecification

import static java.util.concurrent.TimeUnit.MILLISECONDS

class CounterTest extends DDSpecification {

  def "counter counts stuff" () {
    StatsDClient statsd = Mock(StatsDClient)
    Monitoring monitoring = new Monitoring(statsd, 100, MILLISECONDS)
    def counter = monitoring.newCounter("my_counter")
    when:
    counter.increment(1)
    then:
    1 * statsd.count("my_counter", 1, [])
    0 * _
  }

  def "counter tags error counts with cause" () {
    StatsDClient statsd = Mock(StatsDClient)
    Monitoring monitoring = new Monitoring(statsd, 100, MILLISECONDS)
    def counter = monitoring.newCounter("my_counter")
    when:
    counter.incrementErrorCount("bad stuff happened", 1000)
    then:
    1 * statsd.count("my_counter", 1000, ["cause:bad_stuff_happened"])
    0 * _
  }
}
