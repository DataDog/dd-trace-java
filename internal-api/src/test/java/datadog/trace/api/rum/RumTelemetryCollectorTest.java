package datadog.trace.api.rum;

import static datadog.trace.api.rum.RumTelemetryCollector.NO_OP;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.MetricCollector.DistributionSeriesPoint;
import datadog.trace.api.telemetry.MetricCollector.Metric;
import java.util.Collection;
import org.junit.jupiter.api.Test;

class RumTelemetryCollectorTest {

  @Test
  void testDefaultNoOpDoesNotThrowException() {
    assertDoesNotThrow(
        () -> {
          NO_OP.onInjectionSucceed("3");
          NO_OP.onInjectionSucceed("5");
          NO_OP.onInjectionFailed("3", "gzip");
          NO_OP.onInjectionFailed("5", null);
          NO_OP.onInjectionSkipped("3");
          NO_OP.onInjectionSkipped("5");
          NO_OP.onInitializationSucceed();
          NO_OP.onContentSecurityPolicyDetected("3");
          NO_OP.onContentSecurityPolicyDetected("5");
          NO_OP.onInjectionResponseSize("3", 256L);
          NO_OP.onInjectionResponseSize("5", 512L);
          NO_OP.onInjectionTime("3", 10L);
          NO_OP.onInjectionTime("5", 20L);
          NO_OP.close();
        });
  }

  @Test
  void testDefaultNoOpCloseMethodDoesNotThrowException() {
    assertDoesNotThrow(NO_OP::close);
  }

  @Test
  void testDefiningACustomImplementationDoesNotThrowException() {
    RumTelemetryCollector customCollector =
        new RumTelemetryCollector() {
          @Override
          public void onInjectionSucceed(String integrationVersion) {}

          @Override
          public void onInjectionFailed(String integrationVersion, String contentEncoding) {}

          @Override
          public void onInjectionSkipped(String integrationVersion) {}

          @Override
          public void onInitializationSucceed() {}

          @Override
          public void onContentSecurityPolicyDetected(String integrationVersion) {}

          @Override
          public void onInjectionResponseSize(String integrationVersion, long bytes) {}

          @Override
          public void onInjectionTime(String integrationVersion, long milliseconds) {}

          @Override
          public Collection<Metric> drain() {
            return emptyList();
          }

          @Override
          public Collection<DistributionSeriesPoint> drainDistributionSeries() {
            return emptyList();
          }
        };

    assertDoesNotThrow(customCollector::close);
  }

  @Test
  void testMultipleCloseCallsDoNotThrowException() {
    assertDoesNotThrow(
        () -> {
          NO_OP.close();
          NO_OP.close();
          NO_OP.close();
        });
  }

  @Test
  void testDefaultNoOpDrainMethodsReturnEmptyCollections() {
    // when
    Collection<Metric> drainedCounts = NO_OP.drain();
    Collection<DistributionSeriesPoint> drainedDistributions = NO_OP.drainDistributionSeries();

    // then
    assertNotNull(drainedCounts);
    assertTrue(drainedCounts.isEmpty());
    assertNotNull(drainedDistributions);
    assertTrue(drainedDistributions.isEmpty());
  }

  @Test
  void testDefaultNoOpDrainMethodsDoNotThrowException() {
    assertDoesNotThrow(
        () -> {
          NO_OP.drain();
          NO_OP.drain();
          NO_OP.drainDistributionSeries();
          NO_OP.drainDistributionSeries();
        });
  }
}
