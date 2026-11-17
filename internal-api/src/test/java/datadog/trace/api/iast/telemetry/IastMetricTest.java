package datadog.trace.api.iast.telemetry;

import static datadog.trace.api.iast.telemetry.IastMetric.TRACE_METRIC_PREFIX;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.api.Config;
import datadog.trace.api.iast.IastConstantConverter;
import datadog.trace.api.iast.telemetry.IastMetric.Tag;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.EnumSource;
import org.tabletest.junit.TableTest;

class IastMetricTest {

  @ParameterizedTest
  @EnumSource(IastMetric.class)
  void testIastMetricsAttributes(IastMetric metric) throws ReflectiveOperationException {
    Verbosity verbosity = Config.get().getIastTelemetryVerbosity();

    String name = metric.getName();

    assertNotNull(name);

    boolean enabled = metric.isEnabled(verbosity);

    assertEquals(verbosity.isEnabled(verbosityOf(metric)), enabled);
  }

  @TableTest({
    "scenario                | metricTag          | tag                     | expected             ",
    "response header         | VULNERABILITY_TYPE | RESPONSE_HEADER         | RESPONSE_HEADER_TYPES",
    "spring response         | VULNERABILITY_TYPE | SPRING_RESPONSE         | SPRING_RESPONSE_TYPES",
    "application             | VULNERABILITY_TYPE | APPLICATION             | APPLICATION_TYPES    ",
    "sql injection           | VULNERABILITY_TYPE | SQL_INJECTION           |                      ",
    "kafka message           | SOURCE_TYPE        | KAFKA_MESSAGE           | KAFKA_MESSAGE_TYPES  ",
    "request parameter value | SOURCE_TYPE        | REQUEST_PARAMETER_VALUE |                      "
  })
  void testUnwrappingOfTags(
      @ConvertWith(IastConstantConverter.class) Tag metricTag,
      @ConvertWith(IastConstantConverter.class) byte tag,
      @ConvertWith(IastConstantConverter.class) byte[] expected) {
    byte[] result = metricTag.unwrap(tag);

    assertArrayEquals(expected, result);
  }

  @TableTest({
    "scenario            | metric              | tag               ",
    "INSTRUMENTED_SINK   | INSTRUMENTED_SINK   | VULNERABILITY_TYPE",
    "INSTRUMENTED_SOURCE | INSTRUMENTED_SOURCE | SOURCE_TYPE       ",
    "EXECUTED_TAINTED    | EXECUTED_TAINTED    |                   "
  })
  void testMetricTags(IastMetric metric, @ConvertWith(IastConstantConverter.class) Tag tag) {
    List<String> telemetryTags = new ArrayList<>();
    List<String> spanTags = new ArrayList<>();
    if (tag == null) {
      spanTags.add(metric.getSpanTag((byte) -1));
    } else {
      for (int index = 0; index < tag.values.length; index++) {
        telemetryTags.add(metric.getTelemetryTag((byte) index));
        spanTags.add(metric.getSpanTag((byte) index));
      }
    }

    List<String> expectedTelemetryTags = new ArrayList<>();
    List<String> expectedSpanTags = new ArrayList<>();
    if (tag == null) {
      expectedSpanTags.add(TRACE_METRIC_PREFIX + metric.getName());
    } else {
      for (String value : tag.values) {
        expectedTelemetryTags.add(tag.getName() + ":" + value);
        expectedSpanTags.add(
            TRACE_METRIC_PREFIX + metric.getName() + "." + value.toLowerCase().replace('.', '_'));
      }
    }
    assertEquals(expectedTelemetryTags, telemetryTags);
    assertEquals(expectedSpanTags, spanTags);
  }

  private static Verbosity verbosityOf(IastMetric metric) throws ReflectiveOperationException {
    Field field = IastMetric.class.getDeclaredField("verbosity");
    field.setAccessible(true);
    return (Verbosity) field.get(metric);
  }
}
