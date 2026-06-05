package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.core.monitor.HealthMetrics;
import org.junit.jupiter.api.Test;

class PropertyHandlersTest {

  @Test
  void resetReportsBlockedCountForExhaustedHandler() {
    PropertyHandlers handlers = new PropertyHandlers(true);
    // Exhaust span_kind (limit = 8) and record 2 blocked values.
    for (int i = 0; i < MetricCardinalityLimits.SPAN_KIND; i++) {
      handlers.spanKind.register("kind-" + i);
    }
    handlers.spanKind.register("overflow-1");
    handlers.spanKind.register("overflow-2");

    HealthMetrics metrics = mock(HealthMetrics.class);
    handlers.reset(metrics);

    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:span_kind"}, 2L);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void resetReportsBlockedCountForAllNineHandlers() {
    PropertyHandlers handlers = new PropertyHandlers(true);
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
    handlers.reset(metrics);

    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:resource"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:service"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:operation"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:service_source"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:type"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:span_kind"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:http_method"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:http_endpoint"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:grpc_status_code"}, 1L);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void resetRefreshesCapacityForNextCycle() {
    PropertyHandlers handlers = new PropertyHandlers(true);
    for (int i = 0; i < MetricCardinalityLimits.SPAN_KIND; i++) {
      handlers.spanKind.register("kind-" + i);
    }
    assertEquals("blocked_by_tracer", handlers.spanKind.register("overflow").toString());

    handlers.reset(HealthMetrics.NO_OP);

    // Overflow value should now be accepted as a real value.
    assertNotEquals("blocked_by_tracer", handlers.spanKind.register("overflow").toString());
    assertEquals("overflow", handlers.spanKind.register("overflow").toString());
  }

  @Test
  void resetWithNoBlockedValuesDoesNotCallHealthMetrics() {
    PropertyHandlers handlers = new PropertyHandlers(true);
    handlers.resource.register("r1");
    handlers.service.register("svc");

    HealthMetrics metrics = mock(HealthMetrics.class);
    handlers.reset(metrics);

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
