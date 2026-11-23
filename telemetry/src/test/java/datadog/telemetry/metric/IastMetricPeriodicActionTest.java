package datadog.telemetry.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IastMetricPeriodicActionTest {

  private static final IastMetricCollector ORIGINAL_COLLECTOR = IastMetricCollector.get();

  private final IastMetricPeriodicAction action = new IastMetricPeriodicAction();
  private final TelemetryService telemetryService = mock(TelemetryService.class);

  @BeforeEach
  void setUp() {
    IastMetricCollector.register(new IastMetricCollector());
  }

  @AfterEach
  void tearDown() {
    IastMetricCollector.register(ORIGINAL_COLLECTOR);
  }

  @Test
  void testMetric() {
    IastMetric iastMetric = IastMetric.EXECUTED_TAINTED;
    int value = 23;

    IastMetricCollector.add(iastMetric, value);
    IastMetricCollector.get().prepareMetrics();
    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(1)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    assertMetric(captor.getValue(), iastMetric, value, Collections.emptyList());
  }

  @Test
  void testTaggedMetric() {
    IastMetric iastMetric = IastMetric.INSTRUMENTED_SOURCE;
    byte tag = SourceTypes.REQUEST_PARAMETER_VALUE;
    String tagString = SourceTypes.toString(tag);
    int value = 23;

    IastMetricCollector.add(iastMetric, tag, value);
    IastMetricCollector.get().prepareMetrics();
    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(1)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    assertMetric(
        captor.getValue(),
        iastMetric,
        value,
        Arrays.asList(iastMetric.getTag().getName() + ":" + tagString));
  }

  @Test
  void testWithNoMetrics() {
    action.doIteration(telemetryService);

    verifyNoMoreInteractions(telemetryService);
  }

  private void assertMetric(Metric metric, IastMetric iastMetric, long value, List<String> tags) {
    assertEquals("iast", metric.getNamespace());
    assertEquals(iastMetric.getName(), metric.getMetric());
    assertEquals(tags, metric.getTags());
    assertEquals(value, metric.getPoints().get(0).get(1).longValue());
  }
}
