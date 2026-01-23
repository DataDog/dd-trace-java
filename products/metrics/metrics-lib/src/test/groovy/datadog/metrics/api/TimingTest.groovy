package datadog.metrics.api

import datadog.metrics.impl.MonitoringImpl
import datadog.metrics.impl.ThreadLocalRecording
import datadog.metrics.statsd.StatsDClient
import org.junit.jupiter.api.Assertions
import spock.lang.Specification

import static java.util.concurrent.TimeUnit.MILLISECONDS

class TimingTest extends Specification {

  def "timer times stuff"() {
    setup:
    StatsDClient statsd = Mock(StatsDClient)
    MonitoringImpl monitoring = new MonitoringImpl(statsd, 100, MILLISECONDS)
    def timer = monitoring.newTimer("my_timer")
    when:
    Recording recording = timer.start()
    Thread.sleep(200)
    recording.close()
    then:
    1 * statsd.gauge("my_timer", { it > MILLISECONDS.toMicros(200) }, "stat:p50")
    1 * statsd.gauge("my_timer", { it > MILLISECONDS.toMicros(200) }, "stat:p99")
    1 * statsd.gauge("my_timer", { it > MILLISECONDS.toMicros(200) }, "stat:max")
    0 * _
  }

  def "threadlocal timer times stuff"() {
    setup:
    StatsDClient statsd = Mock(StatsDClient)
    MonitoringImpl monitoring = new MonitoringImpl(statsd, 100, MILLISECONDS)
    def timer = monitoring.newThreadLocalTimer("my_timer")
    when:
    Recording recording = timer.start()
    Thread.sleep(200)
    recording.close()
    then:
    1 * statsd.gauge("my_timer", { it > MILLISECONDS.toMicros(200) }, { it[0] == "stat:p50" && it[1].startsWith("thread:") })
    1 * statsd.gauge("my_timer", { it > MILLISECONDS.toMicros(200) }, { it[0] == "stat:p99" && it[1].startsWith("thread:") })
    1 * statsd.gauge("my_timer", { it > MILLISECONDS.toMicros(200) }, { it[0] == "stat:max" && it[1].startsWith("thread:") })
    0 * _
  }

  def "reset timer #iterationIndex"() {
    setup:
    StatsDClient statsd = Mock(StatsDClient)
    MonitoringImpl monitoring = new MonitoringImpl(statsd, 100, MILLISECONDS)
    def timer = timerCreator(monitoring)
    when:
    timer.start()
    long min = System.nanoTime()
    timer.reset()
    then:
    if (timer instanceof ThreadLocalRecording) {
      timer.tls.get().start >= min
    } else {
      timer.start >= min
    }

    where:
    timerCreator << [
      { it.newTimer("my_timer") },
      {
        it.newThreadLocalTimer("my_timer")
      }
    ]
  }

  def "disabled monitoring produces no ops"() {
    expect:
    Monitoring.DISABLED.newTimer("foo") instanceof NoOpRecording
    Monitoring.DISABLED.newTimer("foo", "tag") instanceof NoOpRecording
    Monitoring.DISABLED.newThreadLocalTimer("foo") instanceof NoOpRecording
  }

  def "no ops are safe to use #iterationIndex"() {
    expect:
    try {
      recording.start().stop()
      recording.reset()
    } catch (Throwable t) {
      Assertions.fail(t.getMessage())
    }

    where:
    recording << [
      Monitoring.DISABLED.newTimer("foo"),
      Monitoring.DISABLED.newTimer("foo", "tag"),
      Monitoring.DISABLED.newThreadLocalTimer("foo")
    ]
  }
}

