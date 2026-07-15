package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

public class ConfigStringsTest {

  @TableTest({
    "scenario           | key                        | canonical                  ",
    "property name      | api-key                    | DD_API_KEY                 ",
    "dotted property    | profiling.proxy.password   | DD_PROFILING_PROXY_PASSWORD",
    "dd system property | dd.api-key                 | DD_API_KEY                 ",
    "raw env var        | DD_API_KEY                 | DD_API_KEY                 ",
    "alias env var      | DD_APP_KEY                 | DD_APP_KEY                 ",
    "otel property      | otel.exporter.otlp.headers | OTEL_EXPORTER_OTLP_HEADERS ",
    "otel env var       | OTEL_EXPORTER_OTLP_HEADERS | OTEL_EXPORTER_OTLP_HEADERS ",
    "non-dd env var     | AWS_LAMBDA_FUNCTION_NAME   | DD_AWS_LAMBDA_FUNCTION_NAME"
  })
  void canonicalizesKeysToEnvVarForm(String key, String canonical) {
    assertEquals(canonical, ConfigStrings.toCanonicalEnvVar(key));
  }
}
