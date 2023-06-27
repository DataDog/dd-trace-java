package datadog.telemetry

import datadog.telemetry.metric.MetricPeriodicAction
import datadog.trace.api.telemetry.MetricCollector
import datadog.trace.api.time.TimeSource
import datadog.trace.test.util.DDSpecification

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
    1 * timeSource.getCurrentTimeMillis() >> 60 * 1000 + 1
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
}
