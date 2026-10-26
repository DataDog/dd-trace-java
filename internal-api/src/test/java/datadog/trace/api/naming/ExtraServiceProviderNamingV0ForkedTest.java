package datadog.trace.api.naming;

import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.api.remoteconfig.ServiceNameCollectorTestBridge;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

class ExtraServiceProviderNamingV0ForkedTest extends DDJavaSpecification {

  @Test
  @WithConfig(key = SERVICE_NAME, value = "testService")
  void namingSchemaCallsExtraServicesProviderIfProvidesAServiceName() {
    ServiceNameCollector origServiceNameCollector = ServiceNameCollector.get();
    try {
      ServiceNameCollector extraServiceProvider = mock(ServiceNameCollector.class);
      ServiceNameCollectorTestBridge.setInstance(extraServiceProvider);
      NamingSchema schema = SpanNaming.instance().namingSchema();

      // when
      schema.messaging().inboundService("inboundService", true);
      // then
      verify(extraServiceProvider).addService("inboundService");
      clearInvocations(extraServiceProvider);

      // when
      schema.messaging().inboundService("inboundService", false);
      // then
      verify(extraServiceProvider, never()).addService(any());
      clearInvocations(extraServiceProvider);

      // when
      schema.cache().service("anything");
      // then
      verify(extraServiceProvider).addService("anything");
      clearInvocations(extraServiceProvider);

      // when
      schema.cache().service("hazelcast");
      // then
      verify(extraServiceProvider).addService("hazelcast-sdk");
      clearInvocations(extraServiceProvider);

      // when
      schema.cloud().serviceForRequest("any", null);
      // then
      verify(extraServiceProvider).addService("java-aws-sdk");
      clearInvocations(extraServiceProvider);

      // when
      schema.cloud().serviceForRequest("any", "sns");
      // then
      verify(extraServiceProvider).addService("sns");
      clearInvocations(extraServiceProvider);

      // when
      schema.cloud().serviceForRequest("any", "sqs");
      // then
      verify(extraServiceProvider).addService("sqs");
      clearInvocations(extraServiceProvider);

      // when
      schema.cloud().serviceForRequest("any", "test");
      // then
      verify(extraServiceProvider).addService("java-aws-sdk");
      clearInvocations(extraServiceProvider);

      // when
      schema.database().service("anything");
      // then
      verify(extraServiceProvider).addService("anything");
    } finally {
      ServiceNameCollectorTestBridge.setInstance(origServiceNameCollector);
    }
  }
}
