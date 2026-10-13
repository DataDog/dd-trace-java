package datadog.trace.core.otlp.logs;

import static datadog.trace.api.config.OtlpConfig.OTLP_LOGS_ENDPOINT;
import static datadog.trace.api.config.OtlpConfig.OTLP_LOGS_PROTOCOL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import datadog.trace.api.Config;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class OtlpLogsServiceTest {

  @Test
  void httpJsonProtocolUsesJsonCollectorAndConfiguredEndpoint() {
    Properties properties = new Properties();
    properties.setProperty(OTLP_LOGS_PROTOCOL, "http/json");
    properties.setProperty(OTLP_LOGS_ENDPOINT, "http://localhost:4318/v1/logs");

    OtlpLogsService service = new OtlpLogsService(Config.get(properties));

    assertInstanceOf(OtlpLogsJsonCollector.class, service.getCollector());
    OtlpHttpSender sender = assertInstanceOf(OtlpHttpSender.class, service.getSender());
    assertEquals("http://localhost:4318/v1/logs", sender.url().toString());
  }
}
