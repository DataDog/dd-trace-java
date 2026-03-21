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

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.Monitoring;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.core.test.DDCoreSpecification;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

@Timeout(10)
class TracingConfigPollerTest extends DDCoreSpecification {

  @Test
  void testMergeLibConfigsWithNullAndNonNullValues() {
    TracingConfigPoller.LibConfig config1 = new TracingConfigPoller.LibConfig();
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
    assertEquals(Boolean.TRUE, merged.tracingEnabled);
    assertEquals(Boolean.FALSE, merged.debugEnabled);
    assertEquals(Boolean.TRUE, merged.runtimeMetricsEnabled);
    assertEquals(Boolean.FALSE, merged.logsInjectionEnabled);
    assertEquals(Boolean.TRUE, merged.dataStreamsEnabled);
    assertEquals(0.5, merged.traceSampleRate, 0.001);
    assertEquals(Boolean.TRUE, merged.dynamicInstrumentationEnabled);
    assertEquals(Boolean.FALSE, merged.exceptionReplayEnabled);
    assertEquals(Boolean.TRUE, merged.codeOriginEnabled);
    assertEquals(Boolean.FALSE, merged.liveDebuggingEnabled);
  }

  @TableTest({
    "scenario                      | service      | env     | clusterName  | expectedPriority",
    "service+env                   | test-service | staging | null         | 5               ",
    "service+wildcard env          | test-service | *       | null         | 4               ",
    "wildcard service+env          | *            | staging | null         | 3               ",
    "cluster name                  | null         | null    | test-cluster | 2               ",
    "wildcard service+wildcard env | *            | *       | null         | 1               "
  })
  @ParameterizedTest(name = "[{index}] config priority calculation - {0}")
  void testConfigPriorityCalculation(
      String service, String env, String clusterName, int expectedPriority) {
    TracingConfigPoller.ConfigOverrides configOverrides = new TracingConfigPoller.ConfigOverrides();
    if (!"null".equals(service) || !"null".equals(env)) {
      TracingConfigPoller.ServiceTarget serviceTarget = new TracingConfigPoller.ServiceTarget();
      serviceTarget.service = "null".equals(service) ? null : service;
      serviceTarget.env = "null".equals(env) ? null : env;
      configOverrides.serviceTarget = serviceTarget;
    }
    if (!"null".equals(clusterName)) {
      TracingConfigPoller.ClusterTarget clusterTarget = new TracingConfigPoller.ClusterTarget();
      clusterTarget.clusterName = clusterName;
      clusterTarget.enabled = true;
      TracingConfigPoller.K8sTargetV2 k8sTargetV2 = new TracingConfigPoller.K8sTargetV2();
      k8sTargetV2.clusterTargets = Arrays.asList(clusterTarget);
      configOverrides.k8sTargetV2 = k8sTargetV2;
    }
    configOverrides.libConfig = new TracingConfigPoller.LibConfig();

    int priority = configOverrides.getOverridePriority();

    assertEquals(expectedPriority, priority);
  }

  @Test
  void testActualConfigCommitWithServiceAndOrgLevelConfigs() throws Exception {
    ParsedConfigKey orgKey = ParsedConfigKey.parse("datadog/2/APM_TRACING/org_config/config");
    ParsedConfigKey serviceKey =
        ParsedConfigKey.parse("datadog/2/APM_TRACING/service_config/config");
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    SharedCommunicationObjects sco = createSco(poller);

    AtomicReference<ProductListener> updaterRef = new AtomicReference<>();
    doAnswer(
            inv -> {
              updaterRef.set(inv.getArgument(1));
              return null;
            })
        .when(poller)
        .addListener(eq(Product.APM_TRACING), any(ProductListener.class));

    CoreTracer tracer =
        CoreTracer.builder().sharedCommunicationObjects(sco).pollForTracingConfiguration().build();

    assertTrue(tracer.captureTraceConfig().getServiceMapping().isEmpty());
    assertNull(tracer.captureTraceConfig().getTraceSampleRate());

    ProductListener updater = updaterRef.get();

    // Add org level config (priority 1)
    updater.accept(
        orgKey,
        ("{\n"
                + "  \"service_target\": {\"service\": \"*\", \"env\": \"*\"},\n"
                + "  \"lib_config\": {\n"
                + "    \"tracing_service_mapping\": [{\"from_key\": \"org-service\", \"to_name\": \"org-mapped\"}],\n"
                + "    \"tracing_sampling_rate\": 0.7\n"
                + "  }\n"
                + "}")
            .getBytes(StandardCharsets.UTF_8),
        null);

    // Add service level config (priority 4)
    updater.accept(
        serviceKey,
        ("{\n"
                + "  \"service_target\": {\"service\": \"test-service\", \"env\": \"*\"},\n"
                + "  \"lib_config\": {\n"
                + "    \"tracing_service_mapping\": [{\"from_key\": \"service-specific\", \"to_name\": \"service-mapped\"}],\n"
                + "    \"tracing_header_tags\": [{\"header\": \"X-Custom-Header\", \"tag_name\": \"custom.header\"}],\n"
                + "    \"tracing_sampling_rate\": 1.3,\n"
                + "    \"data_streams_transaction_extractors\": [{\"name\": \"test\", \"type\": \"unknown\", \"value\": \"value\"}]\n"
                + "  }\n"
                + "}")
            .getBytes(StandardCharsets.UTF_8),
        null);

    updater.commit(null);

    // Service level config should take precedence (priority 4 vs 1)
    java.util.Map<String, String> expectedServiceMapping = new java.util.HashMap<>();
    expectedServiceMapping.put("service-specific", "service-mapped");
    assertEquals(expectedServiceMapping, tracer.captureTraceConfig().getServiceMapping());
    assertEquals(1.0, tracer.captureTraceConfig().getTraceSampleRate(), 0.001); // clamped to 1.0

    java.util.Map<String, String> expectedHeaderTags = new java.util.HashMap<>();
    expectedHeaderTags.put("x-custom-header", "custom.header");
    assertEquals(expectedHeaderTags, tracer.captureTraceConfig().getRequestHeaderTags());
    assertEquals(expectedHeaderTags, tracer.captureTraceConfig().getResponseHeaderTags());

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
    java.util.Map<String, String> expectedOrgServiceMapping = new java.util.HashMap<>();
    expectedOrgServiceMapping.put("org-service", "org-mapped");
    assertEquals(expectedOrgServiceMapping, tracer.captureTraceConfig().getServiceMapping());
    assertEquals(0.7, tracer.captureTraceConfig().getTraceSampleRate(), 0.001);
    assertTrue(tracer.captureTraceConfig().getRequestHeaderTags().isEmpty());
    assertTrue(tracer.captureTraceConfig().getResponseHeaderTags().isEmpty());

    // Remove org level config
    updater.remove(orgKey, null);
    updater.commit(null);

    assertTrue(tracer.captureTraceConfig().getServiceMapping().isEmpty());
    assertNull(tracer.captureTraceConfig().getTraceSampleRate());

    tracer.close();
  }

  @Test
  void testTwoOrgLevelConfigsSettingDifferentFlagsWorks() throws Exception {
    ParsedConfigKey orgConfig1Key =
        ParsedConfigKey.parse("datadog/2/APM_TRACING/org_config/config1");
    ParsedConfigKey orgConfig2Key =
        ParsedConfigKey.parse("datadog/2/APM_TRACING/org_config/config2");
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    SharedCommunicationObjects sco = createSco(poller);

    AtomicReference<ProductListener> updaterRef = new AtomicReference<>();
    doAnswer(
            inv -> {
              updaterRef.set(inv.getArgument(1));
              return null;
            })
        .when(poller)
        .addListener(eq(Product.APM_TRACING), any(ProductListener.class));

    CoreTracer tracer =
        CoreTracer.builder().sharedCommunicationObjects(sco).pollForTracingConfiguration().build();

    assertTrue(tracer.captureTraceConfig().isTraceEnabled());
    assertFalse(tracer.captureTraceConfig().isDataStreamsEnabled());

    ProductListener updater = updaterRef.get();

    // Add org level config with tracing_enabled
    updater.accept(
        orgConfig1Key,
        ("{\n"
                + "  \"service_target\": {\"service\": \"*\", \"env\": \"*\"},\n"
                + "  \"lib_config\": {\"tracing_enabled\": true}\n"
                + "}")
            .getBytes(StandardCharsets.UTF_8),
        null);

    // Add second org level config with data_streams_enabled
    updater.accept(
        orgConfig2Key,
        ("{\n"
                + "  \"service_target\": {\"service\": \"*\", \"env\": \"*\"},\n"
                + "  \"lib_config\": {\"data_streams_enabled\": true}\n"
                + "}")
            .getBytes(StandardCharsets.UTF_8),
        null);

    updater.commit(null);

    assertTrue(tracer.captureTraceConfig().isTraceEnabled());
    assertTrue(tracer.captureTraceConfig().isDataStreamsEnabled());

    tracer.close();
  }

  static SharedCommunicationObjects createSco(ConfigurationPoller poller) throws Exception {
    SharedCommunicationObjects sco = new SharedCommunicationObjects();
    sco.agentHttpClient = mock(OkHttpClient.class);
    sco.monitoring = mock(Monitoring.class);
    sco.agentUrl = HttpUrl.get("https://example.com");
    sco.setFeaturesDiscovery(mock(DDAgentFeaturesDiscovery.class));
    java.lang.reflect.Field f =
        SharedCommunicationObjects.class.getDeclaredField("configurationPoller");
    f.setAccessible(true);
    f.set(sco, poller);
    return sco;
  }
}
