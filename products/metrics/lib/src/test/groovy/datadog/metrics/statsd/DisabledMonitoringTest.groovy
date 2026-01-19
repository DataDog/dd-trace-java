package datadog.metrics.statsd

import datadog.metrics.api.Monitoring
import datadog.metrics.api.NoOpCounter
import datadog.metrics.api.NoOpRecording
import org.junit.jupiter.api.Test

class DisabledMonitoringTest {
  Monitoring disabledMonitoring = Monitoring.DISABLED

  @Test
  void 'newTimer returns noop'() {
    assert disabledMonitoring.newTimer('foo').is(NoOpRecording.NO_OP)
    assert disabledMonitoring.newTimer('foo', 'tag').is(NoOpRecording.NO_OP)
    assert disabledMonitoring.newThreadLocalTimer('foo').is(NoOpRecording.NO_OP)
  }

  @Test
  void 'newCounter returns noop'() {
    assert disabledMonitoring.newCounter('foo').is(NoOpCounter.NO_OP)
  }
}
