package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.Monitoring;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.tabletest.junit.TableTest;

@Timeout(10)
public class TracingConfigPollerTest extends DDCoreJavaSpecification {

  @Test
  void mergeLibConfigsWithNullAndNonNullValues() {
    TracingConfigPoller.LibConfig config1 = new TracingConfigPoller.LibConfig(); // all nulls
    TracingConfigPoller.LibConfig config2 = new TracingConfigPoller.LibConfig();
    config2.tracingEnabled = true;
    config2.debugEnabled = false;
    config2.runtimeMetricsEnabled = true;
    config2.logsInjectionEnabled = false;
    config2.dataStreamsEnabled = true;
    config2.traceSampleRate = 0.5;
    config2.dynamicInstrumentationEnabled = true;
    config2.exceptionReplayEnabled = false;
    config2.codeOriginEnabled = true;
    config2.liveDebuggingEnabled = false;

    TracingConfigPoller.LibConfig config3 = new TracingConfigPoller.LibConfig();
    config3.tracingEnabled = false;
    config3.debugEnabled = true;
    config3.runtimeMetricsEnabled = false;
    config3.logsInjectionEnabled = true;
    config3.dataStreamsEnabled = false;
    config3.traceSampleRate = 0.8;
    config3.dynamicInstrumentationEnabled = false;
    config3.exceptionReplayEnabled = true;
    config3.codeOriginEnabled = false;
    config3.liveDebuggingEnabled = true;

    TracingConfigPoller.LibConfig merged =
        TracingConfigPoller.LibConfig.mergeLibConfigs(Arrays.asList(config1, config2, config3));

    assertNotNull(merged);
    // Should take first non-null values from config2
    assertEquals(Boolean.TRUE, merged.tracingEnabled);
    assertEquals(Boolean.FALSE, merged.debugEnabled);
    assertEquals(Boolean.TRUE, merged.runtimeMetricsEnabled);
    assertEquals(Boolean.FALSE, merged.logsInjectionEnabled);
    assertEquals(Boolean.TRUE, merged.dataStreamsEnabled);
    assertEquals(0.5, merged.traceSampleRate);
    assertEquals(Boolean.TRUE, merged.dynamicInstrumentationEnabled);
    assertEquals(Boolean.FALSE, merged.exceptionReplayEnabled);
    assertEquals(Boolean.TRUE, merged.codeOriginEnabled);
    assertEquals(Boolean.FALSE, merged.liveDebuggingEnabled);
  }

  @TableTest({
    "scenario             | service      | env     | clusterName  | expectedPriority",
    "service and env      | test-service | staging |              | 5               ",
    "service and wildcard | test-service | *       |              | 4               ",
    "wildcard and env     | *            | staging |              | 3               ",
    "cluster target       |              |         | test-cluster | 2               ",
    "wildcard org level   | *            | *       |              | 1               "
  })
  void configPriorityCalculation(
      String service, String env, String clusterName, int expectedPriority) {
    TracingConfigPoller.ConfigOverrides configOverrides = new TracingConfigPoller.ConfigOverrides();
    if (service != null || env != null) {
      configOverrides.serviceTarget = new TracingConfigPoller.ServiceTarget();
      configOverrides.serviceTarget.service = service;
      configOverrides.serviceTarget.env = env;
    }
    if (clusterName != null) {
      TracingConfigPoller.ClusterTarget clusterTarget = new TracingConfigPoller.ClusterTarget();
      clusterTarget.clusterName = clusterName;
      clusterTarget.enabled = true;
      configOverrides.k8sTargetV2 = new TracingConfigPoller.K8sTargetV2();
      configOverrides.k8sTargetV2.clusterTargets = Collections.singletonList(clusterTarget);
    }
    configOverrides.libConfig = new TracingConfigPoller.LibConfig();

    assertEquals(expectedPriority, configOverrides.getOverridePriority());
  }

  @Test
  void actualConfigCommitWithServiceAndOrgLevelConfigs() throws Exception {
    ParsedConfigKey orgKey = ParsedConfigKey.parse("datadog/2/APM_TRACING/org_config/config");
    ParsedConfigKey serviceKey =
        ParsedConfigKey.parse("datadog/2/APM_TRACING/service_config/config");
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    SharedCommunicationObjects sco = createScoWithPoller(poller);

    ProductListener[] capturedUpdater = {null};
    doAnswer(
            inv -> {
              // capture config updater for further testing
              capturedUpdater[0] = inv.getArgument(1, ProductListener.class);
              return null;
            })
        .when(poller)
        .addListener(eq(Product.APM_TRACING), any(ProductListener.class));

    CoreTracer tracer =
        CoreTracer.builder().sharedCommunicationObjects(sco).pollForTracingConfiguration().build();
    unclosedTracers.add(tracer);

    try {
      verify(poller).addListener(eq(Product.APM_TRACING), any(ProductListener.class));
      assertNotNull(capturedUpdater[0]);
      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getServiceMapping());
      assertNull(tracer.captureTraceConfig().getTraceSampleRate());

      ProductListener updater = capturedUpdater[0];
      // Add org level config (priority 1) - should set service mapping
      updater.accept(
          orgKey,
          ("{\n"
                  + "  \"service_target\": {\n"
                  + "    \"service\": \"*\",\n"
                  + "    \"env\": \"*\"\n"
                  + "  },\n"
                  + "  \"lib_config\": {\n"
                  + "    \"tracing_service_mapping\": [{\n"
                  + "      \"from_key\": \"org-service\",\n"
                  + "      \"to_name\": \"org-mapped\"\n"
                  + "    }],\n"
                  + "    \"tracing_sampling_rate\": 0.7\n"
                  + "  }\n"
                  + "}")
              .getBytes(StandardCharsets.UTF_8),
          null);
      // Add service level config (priority 4) - should override service mapping and add header tags
      updater.accept(
          serviceKey,
          ("{\n"
                  + "  \"service_target\": {\n"
                  + "    \"service\": \"test-service\",\n"
                  + "    \"env\": \"*\"\n"
                  + "  },\n"
                  + "  \"lib_config\": {\n"
                  + "    \"tracing_service_mapping\": [{\n"
                  + "      \"from_key\": \"service-specific\",\n"
                  + "      \"to_name\": \"service-mapped\"\n"
                  + "    }],\n"
                  + "    \"tracing_header_tags\": [{\n"
                  + "      \"header\": \"X-Custom-Header\",\n"
                  + "      \"tag_name\": \"custom.header\"\n"
                  + "    }],\n"
                  + "    \"tracing_sampling_rate\": 1.3,\n"
                  + "    \"data_streams_transaction_extractors\": [{\n"
                  + "      \"name\": \"test\",\n"
                  + "      \"type\": \"unknown\",\n"
                  + "      \"value\": \"value\"\n"
                  + "    }]\n"
                  + "  }\n"
                  + "}")
              .getBytes(StandardCharsets.UTF_8),
          null);
      // Commit both configs
      updater.commit(null);
      // Service level config should take precedence due to higher priority (4 vs 1)
      assertEquals(
          Collections.singletonMap("service-specific", "service-mapped"),
          tracer.captureTraceConfig().getServiceMapping());
      assertEquals(1.0, tracer.captureTraceConfig().getTraceSampleRate());
      assertEquals(
          Collections.singletonMap("x-custom-header", "custom.header"),
          tracer.captureTraceConfig().getRequestHeaderTags());
      assertEquals(
          Collections.singletonMap("x-custom-header", "custom.header"),
          tracer.captureTraceConfig().getResponseHeaderTags());
      List<DataStreamsTransactionExtractor> extractors =
          tracer.captureTraceConfig().getDataStreamsTransactionExtractors();
      assertEquals(1, extractors.size());
      assertEquals("test", extractors.get(0).getName());
      assertEquals(DataStreamsTransactionExtractor.Type.UNKNOWN, extractors.get(0).getType());
      assertEquals("value", extractors.get(0).getValue());
      // Remove service level config
      updater.remove(serviceKey, null);
      updater.commit(null);
      // Should fall back to org level config
      assertEquals(
          Collections.singletonMap("org-service", "org-mapped"),
          tracer.captureTraceConfig().getServiceMapping());
      assertEquals(0.7, tracer.captureTraceConfig().getTraceSampleRate());
      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getRequestHeaderTags());
      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getResponseHeaderTags());
      // Remove org level config
      updater.remove(orgKey, null);
      updater.commit(null);
      // Should have no configs
      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getServiceMapping());
      assertNull(tracer.captureTraceConfig().getTraceSampleRate());
    } finally {
      tracer.close();
    }
  }

  @Test
  void twoOrgLevelsConfigSettingDifferentFlagsWorks() throws Exception {
    ParsedConfigKey orgConfig1Key =
        ParsedConfigKey.parse("datadog/2/APM_TRACING/org_config/config1");
    ParsedConfigKey orgConfig2Key =
        ParsedConfigKey.parse("datadog/2/APM_TRACING/org_config/config2");
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    SharedCommunicationObjects sco = createScoWithPoller(poller);

    ProductListener[] capturedUpdater = {null};
    doAnswer(
            inv -> {
              // capture config updater for further testing
              capturedUpdater[0] = inv.getArgument(1, ProductListener.class);
              return null;
            })
        .when(poller)
        .addListener(eq(Product.APM_TRACING), any(ProductListener.class));

    CoreTracer tracer =
        CoreTracer.builder().sharedCommunicationObjects(sco).pollForTracingConfiguration().build();
    unclosedTracers.add(tracer);

    try {
      verify(poller).addListener(eq(Product.APM_TRACING), any(ProductListener.class));
      assertTrue(tracer.captureTraceConfig().isTraceEnabled());
      assertFalse(tracer.captureTraceConfig().isDataStreamsEnabled());

      ProductListener updater = capturedUpdater[0];
      // Add org level config with ApmTracing enabled
      updater.accept(
          orgConfig1Key,
          ("{\n"
                  + "  \"service_target\": {\n"
                  + "    \"service\": \"*\",\n"
                  + "    \"env\": \"*\"\n"
                  + "  },\n"
                  + "  \"lib_config\": {\n"
                  + "    \"tracing_enabled\": true\n"
                  + "  }\n"
                  + "}")
              .getBytes(StandardCharsets.UTF_8),
          null);
      // Add second org level config with DataStreams enabled
      updater.accept(
          orgConfig2Key,
          ("{\n"
                  + "  \"service_target\": {\n"
                  + "    \"service\": \"*\",\n"
                  + "    \"env\": \"*\"\n"
                  + "  },\n"
                  + "  \"lib_config\": {\n"
                  + "    \"data_streams_enabled\": true\n"
                  + "  }\n"
                  + "}")
              .getBytes(StandardCharsets.UTF_8),
          null);
      // Commit both configs
      updater.commit(null);
      // Both org level configs should be merged, with data streams enabled
      assertTrue(tracer.captureTraceConfig().isTraceEnabled());
      assertTrue(tracer.captureTraceConfig().isDataStreamsEnabled());
    } finally {
      tracer.close();
    }
  }

  private SharedCommunicationObjects createScoWithPoller(ConfigurationPoller poller)
      throws Exception {
    SharedCommunicationObjects sco = new SharedCommunicationObjects();
    sco.agentHttpClient = mock(OkHttpClient.class);
    sco.monitoring = mock(Monitoring.class);
    sco.agentUrl = HttpUrl.get("https://example.com");
    sco.setFeaturesDiscovery(mock(DDAgentFeaturesDiscovery.class));
    Field pollerField = SharedCommunicationObjects.class.getDeclaredField("configurationPoller");
    pollerField.setAccessible(true);
    pollerField.set(sco, poller);
    return sco;
  }
}
