package datadog.trace.civisibility.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.time.ControllableTimeSource;
import java.io.IOException;
import okhttp3.Call;
import org.junit.jupiter.api.Test;

class TelemetryListenerTest {
  @Test
  void accumulatesFailedAndSuccessfulCallsWithoutRetryBackoff() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    CiVisibilityMetricCollector collector = mock(CiVisibilityMetricCollector.class);
    TelemetryListener listener =
        new TelemetryListener.Builder(collector, timeSource)
            .requestDuration(CiVisibilityDistributionMetric.KNOWN_TESTS_REQUEST_MS)
            .build();
    Call call = mock(Call.class);

    assertEquals(0, listener.getTotalRequestDurationMillis());
    listener.callStart(call);
    timeSource.advance(1_500_000);
    listener.callFailed(call, new IOException("retryable failure"));
    timeSource.advance(1_000_000_000);
    listener.callStart(call);
    timeSource.advance(2_500_000);
    listener.callEnd(call);

    assertEquals(4, listener.getTotalRequestDurationMillis());
    verify(collector).add(CiVisibilityDistributionMetric.KNOWN_TESTS_REQUEST_MS, 1);
    verify(collector).add(CiVisibilityDistributionMetric.KNOWN_TESTS_REQUEST_MS, 2);
  }
}
