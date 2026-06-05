package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.junit.utils.tabletest.BoxedValueConverter;
import datadog.trace.junit.utils.tabletest.ConfigValueConverter;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

public class ConfigSettingTest {

  @TableTest({
    "scenario         | key1 | value1 | origin1  | key2 | value2 | origin2  | expectedEqual",
    "equal            | key  | value  | DEFAULT  | key  | value  | DEFAULT  | true         ",
    "different key    | key  | value  | ENV      | key2 | value  | ENV      | false        ",
    "different value  | key  | value2 | JVM_PROP | key  | value  | JVM_PROP | false        ",
    "different origin | key  | value  | ENV      | key  | value  | DEFAULT  | false        "
  })
  void supportsEqualityCheck(
      String key1,
      Object value1,
      ConfigOrigin origin1,
      String key2,
      Object value2,
      ConfigOrigin origin2,
      boolean expectedEqual) {
    ConfigSetting cs1 = ConfigSetting.of(key1, value1, origin1);
    ConfigSetting cs2 = ConfigSetting.of(key2, value2, origin2);

    if (expectedEqual) {
      assertEquals(cs1.hashCode(), cs2.hashCode());
      assertEquals(cs1, cs2);
      assertEquals(cs2, cs1);
      assertEquals(cs1.toString(), cs2.toString());
    } else {
      assertNotEquals(cs1.hashCode(), cs2.hashCode());
      assertNotEquals(cs1, cs2);
      assertNotEquals(cs2, cs1);
      assertNotEquals(cs1.toString(), cs2.toString());
    }
  }

  @TableTest({
    "scenario             | key                                | value     | filteredValue",
    "dd api key env       | DD_API_KEY                         | somevalue | <hidden>     ",
    "dd api key prop      | dd.api-key                         | somevalue | <hidden>     ",
    "profiling api key    | dd.profiling.api-key               | somevalue | <hidden>     ",
    "profiling apikey     | dd.profiling.apikey                | somevalue | <hidden>     ",
    "application key name | application-key                    | somevalue | <hidden>     ",
    "application key prop | dd.application-key                 | somevalue | <hidden>     ",
    "application key env  | DD_APPLICATION_KEY                 | somevalue | <hidden>     ",
    "app key alias name   | app-key                            | somevalue | <hidden>     ",
    "app key alias prop   | dd.app-key                         | somevalue | <hidden>     ",
    "otlp traces headers  | otlp.traces.headers                | somevalue | <hidden>     ",
    "otlp metrics headers | otlp.metrics.headers               | somevalue | <hidden>     ",
    "otlp logs headers    | otlp.logs.headers                  | somevalue | <hidden>     ",
    "otel otlp headers    | OTEL_EXPORTER_OTLP_HEADERS         | somevalue | <hidden>     ",
    "otel traces headers  | OTEL_EXPORTER_OTLP_TRACES_HEADERS  | somevalue | <hidden>     ",
    "otel metrics headers | OTEL_EXPORTER_OTLP_METRICS_HEADERS | somevalue | <hidden>     ",
    "otel logs headers    | OTEL_EXPORTER_OTLP_LOGS_HEADERS    | somevalue | <hidden>     ",
    "other key            | some.other.key                     | somevalue | somevalue    "
  })
  void filtersKeyValues(String key, String value, String filteredValue) {
    assertEquals(filteredValue, ConfigSetting.of(key, value, ConfigOrigin.DEFAULT).stringValue());
  }

  @TableTest({
    "scenario     | key                                | value                    ",
    "otlp traces  | otlp.traces.headers                | dd-api-key=secret-traces ",
    "otlp metrics | otlp.metrics.headers               | dd-api-key=secret-metrics",
    "otlp logs    | otlp.logs.headers                  | dd-api-key=secret-logs   ",
    "otel base    | OTEL_EXPORTER_OTLP_HEADERS         | dd-api-key=secret-base   ",
    "otel traces  | OTEL_EXPORTER_OTLP_TRACES_HEADERS  | dd-api-key=secret-traces ",
    "otel metrics | OTEL_EXPORTER_OTLP_METRICS_HEADERS | dd-api-key=secret-metrics",
    "otel logs    | OTEL_EXPORTER_OTLP_LOGS_HEADERS    | dd-api-key=secret-logs   ",
    "dd api key   | DD_API_KEY                         | secret-api-key           "
  })
  void doesNotExposeSensitiveValues(String key, String value) {
    String rendered = ConfigSetting.of(key, value, ConfigOrigin.ENV).stringValue();
    assertEquals("<hidden>", rendered);
    assertFalse(
        rendered.contains(value), "rendered telemetry value must not contain the configured value");
  }

  @TableTest({
    "scenario | value  | rendered",
    "null     |        |         ",
    "boolean  | true   | true    ",
    "boolean  | false  | false   ",
    "integer  | 1      | 1       ",
    "double   | 1.0    | 1.0     ",
    "float    | 2.33f  | 2.33    ",
    "string   | string | string  "
  })
  void supportBasicTypes(@ConvertWith(BoxedValueConverter.class) Object value, String rendered) {
    assertEquals(rendered, ConfigSetting.of("key", value, ConfigOrigin.DEFAULT).stringValue());
  }

  @TableTest({
    "scenario      | value                           | rendered              ",
    "iterable      | [1, 2, 3]                       | 1,2,3                 ",
    "decimals      | [1.0, 22.23, 3.1415]            | 1.0,22.23,3.1415      ",
    "map           | [a: 1, b: 2]                    | a:1,b:2               ",
    "empty list    | []                              | ''                    ",
    "empty map     | [:]                             | ''                    ",
    "bitset ranges | bits(33, 200-300, 303, 400-500) | 33,200-300,303,400-500"
  })
  void convertIterableMapAndBitSetToString(
      @ConvertWith(ConfigValueConverter.class) Object value, String rendered) {
    assertEquals(rendered, ConfigSetting.of("key", value, ConfigOrigin.DEFAULT).stringValue());
  }
}
