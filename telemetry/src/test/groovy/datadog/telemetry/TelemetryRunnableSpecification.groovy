package datadog.telemetry

import datadog.trace.test.util.DDSpecification

class TelemetryRunnableSpecification extends DDSpecification {

  TelemetryRunnable.ThreadSleeper sleeper = Mock()
  TelemetryServiceImpl telemetryService = Mock ()
  RequestBuilder requestBuilder = Mock()
  AgentDiscoverer discoverer = Mock {
    telemetryRequestBuilder() >> requestBuilder
  }
  TelemetryRunnable.TelemetryPeriodicAction periodicAction = Mock()
  TelemetryRunnable runnable = new TelemetryRunnable(discoverer, telemetryService, 1, [periodicAction], sleeper)
  Thread t = new Thread(runnable)

  void cleanup() {
    if (t.isAlive()) {
      t.interrupt()
      t.join()
    }
  }

  void 'one loop run with one request'() {

    when:
    t.start()
    t.join()

    then:
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.sendAppStarted(requestBuilder) >> RequestStatus.SUCCESS

    then:
    1 * sleeper.sleep(_)
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.sendTelemetry(requestBuilder) >> RequestStatus.SUCCESS

    then:
    1 * sleeper.sleep(_) >> { t.interrupt() }

    then:
    1 * telemetryService.sendAppClosing(requestBuilder) >> RequestStatus.SUCCESS
  }
}
