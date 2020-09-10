package datadog.trace.core.monitor

import com.timgroup.statsd.StatsDClient
import datadog.trace.util.test.DDSpecification

import static java.util.concurrent.TimeUnit.MILLISECONDS

class TimingTest extends DDSpecification {

  def "timer times stuff" () {
    setup:
    StatsDClient statsd = Mock(StatsDClient)
    Monitoring monitoring = new Monitoring(statsd, 100, MILLISECONDS)
    Timer timer = monitoring.newTimer("my_timer")
    when:
    Recording recording = timer.start()
    Thread.sleep(200)
    recording.close()
    then:
    1 * statsd.time("my_timer", { it > MILLISECONDS.toNanos(200) }, "stat:avg")
    1 * statsd.time("my_timer", { it > MILLISECONDS.toNanos(200) }, "stat:p50")
    1 * statsd.time("my_timer", { it > MILLISECONDS.toNanos(200) }, "stat:p99")
    1 * statsd.time("my_timer", { it > MILLISECONDS.toNanos(200) }, "stat:max")
    0 * _
  }

  def "reset timer" () {
    setup:
    StatsDClient statsd = Mock(StatsDClient)
    Monitoring monitoring = new Monitoring(statsd, 100, MILLISECONDS)
    Timer timer = monitoring.newTimer("my_timer")
    when:
    timer.start()
    long min = System.nanoTime()
    timer.reset()
    then:
    timer.start >= min
  }
}
