package datadog.telemetry

import datadog.telemetry.metric.MetricPeriodicAction
import datadog.trace.api.telemetry.MetricCollector
import datadog.trace.api.time.TimeSource
import spock.lang.Specification

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class TelemetryRunnableSpecification extends Specification {

  static class TickSleeper implements TelemetryRunnable.ThreadSleeper {
    CyclicBarrier sleeped = new CyclicBarrier(2)
    CyclicBarrier go = new CyclicBarrier(2)
    TelemetryRunnable.ThreadSleeper delegate

    @Override
    void sleep(long timeoutMs) {
      delegate?.sleep(timeoutMs)
      sleeped.await(10, TimeUnit.SECONDS)
      go.await(10, TimeUnit.SECONDS)
    }
  }

  Thread t = null

  void cleanup() {
    if (t?.isAlive()) {
      t.interrupt()
      t.join()
    }
  }

  void 'happy path'() {
    setup:
    TelemetryRunnable.ThreadSleeper sleeperMock = Mock()
    TickSleeper sleeper = new TickSleeper(delegate: sleeperMock)
    TimeSource timeSource = Mock()
    TelemetryService telemetryService = Mock(TelemetryService)
    MetricCollector<MetricCollector.Metric> metricCollector = Mock(MetricCollector)
    MetricPeriodicAction metricAction = Stub(MetricPeriodicAction) {
      collector() >> metricCollector
    }
    TelemetryRunnable.TelemetryPeriodicAction periodicAction = Mock(TelemetryRunnable.TelemetryPeriodicAction)
    TelemetryRunnable runnable = new TelemetryRunnable(telemetryService, [metricAction, periodicAction], sleeper, timeSource)
    t = new Thread(runnable)

    when: 'initial iteration before the first sleep (metrics and heartbeat)'
    t.start()
    sleeper.sleeped.await(10, TimeUnit.SECONDS)

    then:
    1 * timeSource.getCurrentTimeMillis() >> 60 * 1000
    _ * telemetryService.addConfiguration(_)

    then:
    1 * metricCollector.prepareMetrics()

    then:
    1 * metricCollector.drain() >> []
    1 * periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.sendIntervalRequests()
    1 * timeSource.getCurrentTimeMillis() >> 60 * 1000 + 1
    1 * sleeperMock.sleep(9999)
    0 * _

    when: 'second iteration (10 seconds, metrics)'
    sleeper.go.await(10, TimeUnit.SECONDS)
    sleeper.sleeped.await(10, TimeUnit.SECONDS)

    then:
    1 * timeSource.getCurrentTimeMillis() >> 70 * 1000

    then:
    1 * metricCollector.prepareMetrics()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 70 * 1000 + 2
    1 * sleeperMock.sleep(9998)
    0 * _

    when: 'third iteration (20 seconds, metrics)'
    sleeper.go.await(10, TimeUnit.SECONDS)
    sleeper.sleeped.await(10, TimeUnit.SECONDS)

    then:
    1 * timeSource.getCurrentTimeMillis() >> 80 * 1000

    then:
    1 * metricCollector.prepareMetrics()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 80 * 1000 + 3
    1 * sleeperMock.sleep(9997)
    0 * _

    when: 'fourth iteration (30 seconds, metrics)'
    sleeper.go.await(10, TimeUnit.SECONDS)
    sleeper.sleeped.await(10, TimeUnit.SECONDS)

    then:
    1 * timeSource.getCurrentTimeMillis() >> 90 * 1000

    then:
    1 * metricCollector.prepareMetrics()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 90 * 1000 + 4
    1 * sleeperMock.sleep(9996)
    0 * _

    when: 'fifth iteration (40 seconds, metrics)'
    sleeper.go.await(10, TimeUnit.SECONDS)
    sleeper.sleeped.await(10, TimeUnit.SECONDS)

    then:
    1 * timeSource.getCurrentTimeMillis() >> 100 * 1000

    then:
    1 * metricCollector.prepareMetrics()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 100 * 1000 + 5
    1 * sleeperMock.sleep(9995)
    0 * _

    when: 'sixth iteration (50 seconds, metrics)'
    sleeper.go.await(10, TimeUnit.SECONDS)
    sleeper.sleeped.await(10, TimeUnit.SECONDS)

    then:
    1 * timeSource.getCurrentTimeMillis() >> 110 * 1000

    then:
    1 * metricCollector.prepareMetrics()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 110 * 1000 + 6
    1 * sleeperMock.sleep(9994)
    0 * _

    when: 'seventh iteration (60 seconds, metrics, heartbeat)'
    sleeper.go.await(10, TimeUnit.SECONDS)
    sleeper.sleeped.await(10, TimeUnit.SECONDS)

    then:
    1 * timeSource.getCurrentTimeMillis() >> 120 * 1000

    then:
    1 * metricCollector.prepareMetrics()

    then:
    1 * metricCollector.drain() >> []
    1 * periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.sendIntervalRequests()
    1 * timeSource.getCurrentTimeMillis() >> 120 * 1000 + 7
    1 * sleeperMock.sleep(9993)
    0 * _

    when:
    t.interrupt()
    t.join()

    then:
    1 * telemetryService.sendAppClosingRequest()
    0 * _
  }

  void 'scheduler skips metrics intervals'() {
    setup:
    TimeSource timeSource = Mock()
    TickSleeper sleeper = Mock()
    TelemetryRunnable.Scheduler scheduler = new TelemetryRunnable.Scheduler(timeSource, sleeper, 60 * 1000, 10 * 1000)

    when: 'first iteration'
    scheduler.init()

    then: 'run everything'
    timeSource.getCurrentTimeMillis() >> 0
    scheduler.shouldRunMetrics()
    scheduler.shouldRunHeartbeat()
    0 * _

    when:
    scheduler.sleepUntilNextIteration()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 1
    1 * sleeper.sleep(10 * 1000 - 1)
    1 * timeSource.getCurrentTimeMillis() >> 10 * 1000
    0 * _

    when: 'one metrics interval is exceeded'
    assert scheduler.shouldRunMetrics()
    assert !scheduler.shouldRunHeartbeat()
    scheduler.sleepUntilNextIteration()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 20 * 1000 + 1
    1 * sleeper.sleep(9999)
    1 * timeSource.getCurrentTimeMillis() >> 30 * 1000
    0 * _

    when: 'two metrics interval are exceeded'
    assert scheduler.shouldRunMetrics()
    assert !scheduler.shouldRunHeartbeat()
    scheduler.sleepUntilNextIteration()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 50 * 1000 + 2
    1 * sleeper.sleep(9998)
    1 * timeSource.getCurrentTimeMillis() >> 60 * 1000
    0 * _
    scheduler.shouldRunMetrics()
    scheduler.shouldRunHeartbeat()
  }

  void 'scheduler skips heartbeat intervals'() {
    setup:
    TimeSource timeSource = Mock()
    TickSleeper sleeper = Mock()
    TelemetryRunnable.Scheduler scheduler = new TelemetryRunnable.Scheduler(timeSource, sleeper, 60 * 1000, 10 * 1000)

    when: 'first iteration'
    scheduler.init()

    then: 'run everything'
    timeSource.getCurrentTimeMillis() >> 0
    scheduler.shouldRunMetrics()
    scheduler.shouldRunHeartbeat()
    0 * _

    when:
    scheduler.sleepUntilNextIteration()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 1
    1 * sleeper.sleep(10 * 1000 - 1)
    1 * timeSource.getCurrentTimeMillis() >> 10 * 1000
    0 * _

    when: 'heartbeat interval is exceeded'
    assert scheduler.shouldRunMetrics()
    assert !scheduler.shouldRunHeartbeat()
    scheduler.sleepUntilNextIteration()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 70 * 1000
    0 * _
    scheduler.shouldRunMetrics()
    scheduler.shouldRunHeartbeat()

    when: 'metrics interval has been adjusted'
    scheduler.sleepUntilNextIteration()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 70 * 1000 + 1
    1 * sleeper.sleep(10 * 1000 - 1)
    1 * timeSource.getCurrentTimeMillis() >> 80 * 1000
    0 * _
    scheduler.shouldRunMetrics()
    !scheduler.shouldRunHeartbeat()
  }

  void 'scheduler with heartbeat #heartbeatSecs and metrics #metricsSecs'() {
    setup:
    TimeSourceAndSleeper timing = new TimeSourceAndSleeper()
    TelemetryRunnable.Scheduler scheduler = new TelemetryRunnable.Scheduler(timing, timing, heartbeatSecs * 1000, metricsSecs * 1000)
    def metricsRun = []
    def heartbeatsRun = []

    when:
    scheduler.init()

    and:
    iters.times {
      metricsRun.add(scheduler.shouldRunMetrics())
      heartbeatsRun.add(scheduler.shouldRunHeartbeat())
      scheduler.sleepUntilNextIteration()
    }

    then:
    metricsRun.size() == iters
    heartbeatsRun.size() == iters
    metricsRun.count { it } == expectedMetrics
    heartbeatsRun.count { it } == expectedHeartbeats

    where:
    heartbeatSecs | metricsSecs | expectedHeartbeats | expectedMetrics | iters
    1             | 1           | 10                 | 10              | 10
    60            | 10          | 2                  | 12              | 12
    10            | 60          | 12                 | 2               | 12
    5             | 3           | 3                  | 4               | 6
    3             | 5           | 4                  | 3               | 6
  }

  class TimeSourceAndSleeper implements TimeSource, TelemetryRunnable.ThreadSleeper {

    private long currentTime = 0

    @Override
    void sleep(long timeoutMs) {
      currentTime += timeoutMs
    }

    @Override
    long getCurrentTimeMillis() {
      return currentTime
    }

    @Override
    long getNanoTicks() {
      throw new RuntimeException("NOT IMPLEMENTED")
    }

    @Override
    long getCurrentTimeMicros() {
      throw new RuntimeException("NOT IMPLEMENTED")
    }

    @Override
    long getCurrentTimeNanos() {
      throw new RuntimeException("NOT IMPLEMENTED")
    }
  }
}
