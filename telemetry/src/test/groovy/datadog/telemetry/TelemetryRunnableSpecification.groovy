package datadog.telemetry

import datadog.telemetry.metric.MetricPeriodicAction
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.telemetry.MetricCollector
import datadog.trace.api.time.TimeSource
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.ConfigStrings

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class TelemetryRunnableSpecification extends DDSpecification {

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
    injectEnvConfig(ConfigStrings.toEnvVar(GeneralConfig.TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL), "65")
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

    then: 'two unsuccessful attempts to send app-started with the following successful attempt'
    3 * telemetryService.sendAppStartedEvent() >>> [false, false, true]
    1 * timeSource.getCurrentTimeMillis() >> 60 * 1000
    _ * telemetryService.addConfiguration(_)

    then:
    1 * metricCollector.prepareMetrics()

    then:
    1 * metricCollector.drain() >> []
    1 * metricCollector.drainDistributionSeries() >> []
    1 * periodicAction.doIteration(telemetryService)

    then: 'two partial and one final telemetry data requests'
    3 * telemetryService.sendTelemetryEvents() >>> [true, true, false]
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
    1 * metricCollector.drainDistributionSeries() >> []
    1 * periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.sendTelemetryEvents()
    1 * timeSource.getCurrentTimeMillis() >> 120 * 1000 + 7
    1 * sleeperMock.sleep(9993)

    when: 'eights iteration (65 seconds, extended-heartbeat)'
    sleeper.go.await(5, TimeUnit.SECONDS)
    sleeper.sleeped.await(5, TimeUnit.SECONDS)

    then:
    1 * timeSource.getCurrentTimeMillis() >> 125 * 1000

    then:
    1 * telemetryService.sendExtendedHeartbeat()

    then:
    1 * timeSource.getCurrentTimeMillis() >> 125 * 1000 + 8
    1 * sleeperMock.sleep(4992)
    0 * _

    when:
    t.interrupt()
    t.join()

    // flush pending data before shutdown
    then:
    1 * metricCollector.prepareMetrics()
    1 * metricCollector.drain() >> []
    1 * metricCollector.drainDistributionSeries() >> []
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.sendTelemetryEvents()

    then:
    1 * telemetryService.sendAppClosingEvent()
    0 * _
  }

  void 'do not reattempt app-started event until next cycle'() {
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

    then: 'three unsuccessful attempts to send app-started (TelemetryRunnable.MAX_APP_STARTED_RETRIES) with following successful attempt'
    3 * telemetryService.sendAppStartedEvent() >>> [false, false, false]
    2 * timeSource.getCurrentTimeMillis() >> 60 * 1000
    1 * sleeperMock.sleep(10000)
  }

  void 'scheduler skips metrics intervals'() {
    setup:
    TimeSource timeSource = Mock()
    TickSleeper sleeper = Mock()
    TelemetryRunnable.Scheduler scheduler = new TelemetryRunnable.Scheduler(timeSource, sleeper, 60 * 1000, 10 * 1000, 0)

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
    TelemetryRunnable.Scheduler scheduler = new TelemetryRunnable.Scheduler(timeSource, sleeper, 60 * 1000, 10 * 1000, 0)

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

  void 'scheduler with heartbeat #heartbeatSecs and metrics #metricsSecs and extended-heartbeat #extHeartbeatSecs'() {
    setup:
    TimeSourceAndSleeper timing = new TimeSourceAndSleeper()
    TelemetryRunnable.Scheduler scheduler = new TelemetryRunnable.Scheduler(timing, timing, heartbeatSecs * 1000, metricsSecs * 1000, extHeartbeatSecs * 1000)
    def metricsRun = []
    def heartbeatsRun = []
    def extHeartbeatsRun = []

    when:
    scheduler.init()

    and:
    iters.times {
      metricsRun.add(scheduler.shouldRunMetrics())
      heartbeatsRun.add(scheduler.shouldRunHeartbeat())
      def runExtHeartbeat = scheduler.shouldRunExtendedHeartbeat()
      extHeartbeatsRun.add(runExtHeartbeat)
      if (runExtHeartbeat) {
        // need to manually advance to retry next iteration if extended-heartbeat request failed
        scheduler.scheduleNextExtendedHeartbeat()
      }
      scheduler.sleepUntilNextIteration()
    }

    then:
    metricsRun.size() == iters
    heartbeatsRun.size() == iters
    metricsRun.count { it } == expectedMetrics
    heartbeatsRun.count { it } == expectedHeartbeats
    extHeartbeatsRun.count { it } == expectedExtHeartbeats

    where:
    iters | metricsSecs | heartbeatSecs | extHeartbeatSecs | expectedMetrics | expectedHeartbeats | expectedExtHeartbeats
    10    | 0           | 0             | 0                | 10              | 10                 | 10
    10    | 1           | 1             | 1                | 10              | 10                 | 9
    12    | 10          | 60            | 60               | 12              | 2                  | 1
    12    | 60          | 10            | 10               | 2               | 12                 | 11
    6     | 3           | 5             | 5                | 4               | 3                  | 2
    6     | 5           | 3             | 3                | 3               | 4                  | 3
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
