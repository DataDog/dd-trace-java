package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.OtlpConfig.OTLP_PROFILES_COMPRESSION;
import static datadog.trace.api.config.OtlpConfig.OTLP_PROFILES_ENDPOINT;
import static datadog.trace.api.config.OtlpConfig.OTLP_PROFILES_HEADERS;
import static datadog.trace.api.config.OtlpConfig.OTLP_PROFILES_PROTOCOL;
import static datadog.trace.api.config.OtlpConfig.OTLP_PROFILES_TIMEOUT;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class OtelProfilesEnvironmentConfigSourceTest {

  @Test
  void profilesKeysAreNotMappedWhenOtlpProfilingIsDisabled() {
    injectEnvConfig("OTEL_EXPORTER_OTLP_PROFILES_ENDPOINT", "http://collector:4318/p", false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertNull(source.get(OTLP_PROFILES_ENDPOINT));
  }

  @Test
  void signalSpecificProfilesKeysAreMapped() {
    injectEnvConfig("DD_PROFILING_OTLP_ENABLED", "true", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_PROFILES_ENDPOINT", "http://collector:4318/p", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_PROFILES_HEADERS", "api-key=secret", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_PROFILES_PROTOCOL", "http/protobuf", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_PROFILES_COMPRESSION", "gzip", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_PROFILES_TIMEOUT", "5000", false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("http://collector:4318/p", source.get(OTLP_PROFILES_ENDPOINT));
    assertEquals("api-key=secret", source.get(OTLP_PROFILES_HEADERS));
    assertEquals("http/protobuf", source.get(OTLP_PROFILES_PROTOCOL));
    assertEquals("gzip", source.get(OTLP_PROFILES_COMPRESSION));
    assertEquals("5000", source.get(OTLP_PROFILES_TIMEOUT));
  }

  @Test
  void generalEndpointGetsProfilesPathAppended() {
    injectEnvConfig("DD_PROFILING_OTLP_ENABLED", "true", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_HEADERS", "api-key=secret", false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals(
        "http://collector:4318/v1development/profiles", source.get(OTLP_PROFILES_ENDPOINT));
    assertEquals("api-key=secret", source.get(OTLP_PROFILES_HEADERS));
  }

  @Test
  void generalEndpointIsKeptAsIsForGrpc() {
    injectEnvConfig("DD_PROFILING_OTLP_ENABLED", "true", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4317", false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("http://collector:4317", source.get(OTLP_PROFILES_ENDPOINT));
  }

  @Test
  void generalEndpointIsKeptAsIsForDatadogGrpcProtocol() {
    injectEnvConfig("DD_PROFILING_OTLP_ENABLED", "true", false);
    injectEnvConfig("DD_OTLP_PROFILES_PROTOCOL", "grpc", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4317", false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("http://collector:4317", source.get(OTLP_PROFILES_ENDPOINT));
  }

  @Test
  void otlpProfilingDoesNotMapGeneralOtelEnvironment() {
    injectEnvConfig("DD_PROFILING_OTLP_ENABLED", "true", false);
    injectEnvConfig("OTEL_SERVICE_NAME", "otel-service", false);
    injectEnvConfig("OTEL_METRICS_EXPORTER", "none", false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertNull(source.get(SERVICE_NAME));
    assertNull(source.get(RUNTIME_METRICS_ENABLED));
  }

  @Test
  void profilesKeysAreMappedWhenOtelSdkIsDisabled() {
    injectEnvConfig("DD_PROFILING_OTLP_ENABLED", "true", false);
    injectEnvConfig("OTEL_SDK_DISABLED", "true", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_PROFILES_ENDPOINT", "http://collector:4318/p", false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("http://collector:4318/p", source.get(OTLP_PROFILES_ENDPOINT));
  }

  @Test
  void datadogSettingWinsOverOtelSetting() {
    injectEnvConfig("DD_PROFILING_OTLP_ENABLED", "true", false);
    injectEnvConfig("DD_OTLP_PROFILES_ENDPOINT", "http://dd:4318/p", false);
    injectEnvConfig("OTEL_EXPORTER_OTLP_PROFILES_ENDPOINT", "http://collector:4318/p", false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertNull(source.get(OTLP_PROFILES_ENDPOINT));
  }
}
