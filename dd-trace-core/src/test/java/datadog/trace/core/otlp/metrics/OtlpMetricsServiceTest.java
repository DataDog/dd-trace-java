package datadog.trace.core.otlp.metrics;

import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_ENDPOINT;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_PROTOCOL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import datadog.trace.api.Config;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class OtlpMetricsServiceTest {

  @Test
  void httpJsonProtocolUsesJsonCollectorAndConfiguredEndpoint() {
    Properties properties = new Properties();
    properties.setProperty(OTLP_METRICS_PROTOCOL, "http/json");
    properties.setProperty(OTLP_METRICS_ENDPOINT, "http://localhost:4318/v1/metrics");

    OtlpMetricsService service = new OtlpMetricsService(Config.get(properties));

    assertInstanceOf(OtlpMetricsJsonCollector.class, service.getCollector());
    OtlpHttpSender sender = assertInstanceOf(OtlpHttpSender.class, service.getSender());
    assertEquals("http://localhost:4318/v1/metrics", sender.url().toString());
  }
}
