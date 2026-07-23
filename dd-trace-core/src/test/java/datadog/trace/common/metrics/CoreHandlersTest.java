package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.core.monitor.HealthMetrics;
import org.junit.jupiter.api.Test;

class CoreHandlersTest {

  @Test
  void resetReportsBlockedCountForExhaustedHandler() {
    CoreHandlers handlers = new CoreHandlers();
    // Exhaust span_kind (limit = 8) and record 2 blocked values.
    for (int i = 0; i < MetricCardinalityLimits.SPAN_KIND; i++) {
      handlers.spanKind.register("kind-" + i);
    }
    handlers.spanKind.register("overflow-1");
    handlers.spanKind.register("overflow-2");

    HealthMetrics metrics = mock(HealthMetrics.class);
    handlers.reset(metrics, new CardinalityLimitReporter());

    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:span_kind"}, 2L);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void resetReportsBlockedCountForAllNineHandlers() {
    CoreHandlers handlers = new CoreHandlers();
    exhaustAndBlock(handlers.resource, MetricCardinalityLimits.RESOURCE);
    exhaustAndBlock(handlers.service, MetricCardinalityLimits.SERVICE);
    exhaustAndBlock(handlers.operation, MetricCardinalityLimits.OPERATION);
    exhaustAndBlock(handlers.serviceSource, MetricCardinalityLimits.SERVICE_SOURCE);
    exhaustAndBlock(handlers.type, MetricCardinalityLimits.TYPE);
    exhaustAndBlock(handlers.spanKind, MetricCardinalityLimits.SPAN_KIND);
    exhaustAndBlock(handlers.httpMethod, MetricCardinalityLimits.HTTP_METHOD);
    exhaustAndBlock(handlers.httpEndpoint, MetricCardinalityLimits.HTTP_ENDPOINT);
    exhaustAndBlock(handlers.grpcStatusCode, MetricCardinalityLimits.GRPC_STATUS_CODE);

    HealthMetrics metrics = mock(HealthMetrics.class);
    handlers.reset(metrics, new CardinalityLimitReporter());

    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:resource"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:service"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:operation"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:service_source"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:type"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:span_kind"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:http_method"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:http_endpoint"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:grpc_status_code"}, 1L);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void resetRefreshesCapacityForNextCycle() {
    CoreHandlers handlers = new CoreHandlers();
    for (int i = 0; i < MetricCardinalityLimits.SPAN_KIND; i++) {
      handlers.spanKind.register("kind-" + i);
    }
    assertEquals("tracer_blocked_value", handlers.spanKind.register("overflow").toString());

    handlers.reset(HealthMetrics.NO_OP, new CardinalityLimitReporter());

    // Overflow value should now be accepted as a real value.
    assertNotEquals("tracer_blocked_value", handlers.spanKind.register("overflow").toString());
    assertEquals("overflow", handlers.spanKind.register("overflow").toString());
  }

  @Test
  void resetWithNoBlockedValuesDoesNotCallHealthMetrics() {
    CoreHandlers handlers = new CoreHandlers();
    handlers.resource.register("r1");
    handlers.service.register("svc");

    HealthMetrics metrics = mock(HealthMetrics.class);
    handlers.reset(metrics, new CardinalityLimitReporter());

    verifyNoMoreInteractions(metrics);
  }

  /** Fills {@code handler} to its cardinality limit then registers one more to block it. */
  private static void exhaustAndBlock(PropertyCardinalityHandler handler, int limit) {
    for (int i = 0; i < limit; i++) {
      handler.register("value-" + i);
    }
    handler.register("overflow");
  }
}
