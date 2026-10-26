package datadog.trace.api.naming;

import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.TracerConfig.TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

class NamingV0ForkedTest extends DDJavaSpecification {

  @Test
  @WithConfig(key = TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED, value = "true")
  @WithConfig(key = SERVICE_NAME, value = "testService")
  void v0ShouldNotSetServiceWhenRemoveIntegrationServiceNamesIsEnabled() {
    // when
    NamingSchema schema = SpanNaming.instance().namingSchema();

    // then
    assertEquals(0, SpanNaming.instance().version());
    assertFalse(schema.allowInferredServices());
    assertNull(schema.messaging().inboundService("anything", true).get());
    assertNull(schema.messaging().outboundService("anything", true).get());
    assertNull(schema.database().service("anything"));
    assertNull(schema.cache().service("anything"));
    assertNull(schema.cloud().serviceForRequest("any", "anything"));
  }
}
