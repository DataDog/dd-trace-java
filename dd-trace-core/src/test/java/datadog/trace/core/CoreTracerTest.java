package datadog.trace.core;

import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import datadog.environment.JavaVirtualMachine;
import datadog.metrics.api.Monitoring;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ServiceNameSources;
import datadog.trace.common.sampling.AllSampler;
import datadog.trace.common.sampling.RateByServiceTraceSampler;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreTracer.ConfigSnapshot;
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory;
import datadog.trace.junit.utils.config.WithConfig;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class CoreTracerTest extends DDCoreJavaSpecification {

  @BeforeAll
  static void checkJvm() {
    Assumptions.assumeFalse(
        JavaVirtualMachine.isOracleJDK8(),
        "Oracle JDK 1.8 did not merge the fix in JDK-8058322, leading to the JVM failing to"
            + " correctly extract method parameters without args, when the code is compiled on a"
            + " later JDK (targeting 8). This can manifest when creating mocks.");
  }

  @Test
  void verifyDefaultsOnTracer() {
    CoreTracer tracer = CoreTracer.builder().build();
    try {
      assertFalse(tracer.serviceName.isEmpty());
      assertInstanceOf(RateByServiceTraceSampler.class, tracer.initialSampler);
      assertInstanceOf(DDAgentWriter.class, tracer.writer);
    } finally {
      tracer.close();
    }
  }

  @Test
  @WithConfig(key = TracerConfig.PRIORITY_SAMPLING, value = "false")
  void verifyOverridingSampler() {
    CoreTracer tracer = tracerBuilder().build();
    try {
      assertInstanceOf(AllSampler.class, tracer.initialSampler);
    } finally {
      tracer.close();
    }
  }

  @Test
  @WithConfig(key = TracerConfig.WRITER_TYPE, value = "LoggingWriter")
  void verifyOverridingWriter() {
    CoreTracer tracer = tracerBuilder().build();
    try {
      assertInstanceOf(LoggingWriter.class, tracer.writer);
    } finally {
      tracer.close();
    }
  }

  @Test
  @WithConfig(key = TracerConfig.AGENT_UNIX_DOMAIN_SOCKET, value = "asdf")
  void verifyUdsWindows() {
    String originalOsName = System.getProperty("os.name");
    try {
      System.setProperty("os.name", "Windows ME");
      assertEquals("asdf", Config.get().getAgentUnixDomainSocket());
    } finally {
      if (originalOsName != null) {
        System.setProperty("os.name", originalOsName);
      } else {
        System.clearProperty("os.name");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("verifyMappingConfigsOnTracerArguments")
  void verifyMappingConfigsOnTracer(String scenario, String mapString, Map<String, String> map) {
    injectSysConfig(TracerConfig.SERVICE_MAPPING, mapString);
    injectSysConfig(TracerConfig.SPAN_TAGS, mapString);
    injectSysConfig(TracerConfig.HEADER_TAGS, mapString);
    CoreTracer tracer = tracerBuilder().build();
    try {
      ConfigSnapshot config = tracer.captureTraceConfig();
      assertEquals(map, config.mergedTracerTags);
      assertEquals(map, config.getServiceMapping());
    } finally {
      tracer.close();
    }
  }

  static Stream<Object[]> verifyMappingConfigsOnTracerArguments() {
    return Stream.of(
        new Object[] {"duplicate keys", "a:one, a:two, a:three", buildStringMap("a", "three")},
        new Object[] {"empty value", "a:b,c:d,e:", buildStringMap("a", "b", "c", "d")});
  }

  @ParameterizedTest
  @MethodSource("verifyBaggageMappingConfigsOnTracerArguments")
  void verifyBaggageMappingConfigsOnTracer(
      String scenario, String mapString, Map<String, String> map) {
    injectSysConfig(TracerConfig.BAGGAGE_MAPPING, mapString);
    CoreTracer tracer = tracerBuilder().build();
    try {
      assertEquals(map, tracer.captureTraceConfig().getBaggageMapping());
    } finally {
      tracer.close();
    }
  }

  static Stream<Object[]> verifyBaggageMappingConfigsOnTracerArguments() {
    return Stream.of(
        new Object[] {"duplicate keys", "a:one, a:two, a:three", buildStringMap("a", "three")},
        new Object[] {"empty value", "a:b,c:d,e:", buildStringMap("a", "b", "c", "d")});
  }

  @Test
  @WithConfig(key = "agent.host", value = "somethingelse")
  void verifyOverridingHost() {
    assertEquals("somethingelse", Config.get().getAgentHost());
  }

  @TableTest({
    "scenario   | key              | value",
    "agent port | agent.port       | 777  ",
    "trace port | trace.agent.port | 9999 "
  })
  void verifyOverridingPort(String key, String value) {
    injectSysConfig(key, value);
    assertEquals(Integer.valueOf(value), Config.get().getAgentPort());
  }

  @Test
  @WithConfig(key = "writer.type", value = "LoggingWriter")
  void writerIsLoggingWriterWhenPropertySet() {
    CoreTracer tracer = tracerBuilder().build();
    try {
      assertInstanceOf(LoggingWriter.class, tracer.writer);
    } finally {
      tracer.close();
    }
  }

  @TableTest({
    "scenario       | key               | value",
    "priority true  | priority.sampling | true ",
    "priority false | priority.sampling | false"
  })
  void sharesTraceCountWithDDApiWithKeyValue(String key, String value) {
    injectSysConfig(key, value);
    CoreTracer tracer = tracerBuilder().build();
    try {
      assertInstanceOf(DDAgentWriter.class, tracer.writer);
    } finally {
      tracer.close();
    }
  }

  @Test
  void rootTagsAppliedOnlyToRootSpans() {
    Map<String, Object> localRootSpanTags = new LinkedHashMap<>();
    localRootSpanTags.put("only_root", "value");
    CoreTracer tracer = tracerBuilder().localRootSpanTags(localRootSpanTags).build();
    AgentSpan root = tracer.buildSpan("my_root").start();
    AgentSpan child = tracer.buildSpan("my_child").asChildOf(root.context()).start();
    try {
      assertTrue(root.getTags().containsKey("only_root"));
      assertFalse(child.getTags().containsKey("only_root"));
    } finally {
      child.finish();
      root.finish();
      tracer.close();
    }
  }

  @Test
  void prioritySamplingWhenSpanFinishes() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("operation").start();
      span.finish();
      writer.waitForTraces(1);
      assertEquals(PrioritySampling.SAMPLER_KEEP, (int) span.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  @Test
  void prioritySamplingSetWhenChildSpanComplete() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      DDSpan root = (DDSpan) tracer.buildSpan("operation").start();
      DDSpan child = (DDSpan) tracer.buildSpan("my_child").asChildOf(root.context()).start();
      root.finish();

      assertNull(root.getSamplingPriority());

      child.finish();
      writer.waitForTraces(1);

      assertEquals(PrioritySampling.SAMPLER_KEEP, (int) root.getSamplingPriority());
      assertEquals(root.getSamplingPriority(), child.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  @Test
  void verifyConfigurationPolling() throws Exception {
    ParsedConfigKey key = ParsedConfigKey.parse("datadog/2/APM_TRACING/config_overrides/config");
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    SharedCommunicationObjects sco = createScoWithPoller(poller);

    ProductListener[] capturedUpdater = {null};
    doAnswer(
            inv -> {
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
      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getRequestHeaderTags());
      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getResponseHeaderTags());
      assertNull(tracer.captureTraceConfig().getTraceSampleRate());

      String json =
          "{\n"
              + "  \"lib_config\":\n"
              + "  {\n"
              + "    \"tracing_service_mapping\":\n"
              + "    [{\n"
              + "       \"from_key\": \"foobar\",\n"
              + "       \"to_name\": \"bar\"\n"
              + "    }, {\n"
              + "       \"from_key\": \"snafu\",\n"
              + "       \"to_name\": \"foo\"\n"
              + "    }]\n"
              + "    ,\n"
              + "    \"tracing_header_tags\":\n"
              + "    [{\n"
              + "       \"header\": \"Cookie\",\n"
              + "       \"tag_name\": \"\"\n"
              + "    }, {\n"
              + "       \"header\": \"Referer\",\n"
              + "       \"tag_name\": \"http.referer\"\n"
              + "    }, {\n"
              + "       \"header\": \"  Some.Header  \",\n"
              + "       \"tag_name\": \"\"\n"
              + "    }, {\n"
              + "       \"header\": \"C!!!ont_____ent----tYp!/!e\",\n"
              + "       \"tag_name\": \"\"\n"
              + "    }, {\n"
              + "       \"header\": \"this.header\",\n"
              + "       \"tag_name\": \"whatever.the.user.wants.this.header\"\n"
              + "    }]\n"
              + "    ,\n"
              + "    \"tracing_sampling_rate\": 0.5\n"
              + "  }\n"
              + "}";

      capturedUpdater[0].accept(key, json.getBytes(StandardCharsets.UTF_8), null);
      capturedUpdater[0].commit(null);

      Map<String, String> expectedServiceMapping = buildStringMap("foobar", "bar", "snafu", "foo");
      assertEquals(expectedServiceMapping, tracer.captureTraceConfig().getServiceMapping());

      Map<String, String> expectedRequestHeaderTags =
          buildStringMap(
              "cookie", "http.request.headers.cookie",
              "referer", "http.referer",
              "some.header", "http.request.headers.some_header",
              "c!!!ont_____ent----typ!/!e", "http.request.headers.c___ont_____ent----typ_/_e",
              "this.header", "whatever.the.user.wants.this.header");
      assertEquals(expectedRequestHeaderTags, tracer.captureTraceConfig().getRequestHeaderTags());

      Map<String, String> expectedResponseHeaderTags =
          buildStringMap(
              "cookie", "http.response.headers.cookie",
              "referer", "http.referer",
              "some.header", "http.response.headers.some_header",
              "c!!!ont_____ent----typ!/!e", "http.response.headers.c___ont_____ent----typ_/_e",
              "this.header", "whatever.the.user.wants.this.header");
      assertEquals(expectedResponseHeaderTags, tracer.captureTraceConfig().getResponseHeaderTags());

      assertEquals(0.5, tracer.captureTraceConfig().getTraceSampleRate(), 0.0001);

      capturedUpdater[0].remove(key, null);
      capturedUpdater[0].commit(null);

      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getServiceMapping());
      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getRequestHeaderTags());
      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getResponseHeaderTags());
      assertNull(tracer.captureTraceConfig().getTraceSampleRate());
    } finally {
      tracer.close();
    }
  }

  @ParameterizedTest
  @MethodSource("verifyConfigurationPollingWithCustomTagsArguments")
  void verifyConfigurationPollingWithCustomTags(
      String scenario, String json, Map<String, String> expectedValue) throws Exception {
    ParsedConfigKey key = ParsedConfigKey.parse("datadog/2/APM_TRACING/config_overrides/config");
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    SharedCommunicationObjects sco = createScoWithPoller(poller);

    ProductListener[] capturedUpdater = {null};
    doAnswer(
            inv -> {
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
      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getTracingTags());

      capturedUpdater[0].accept(key, json.getBytes(StandardCharsets.UTF_8), null);
      capturedUpdater[0].commit(null);

      ConfigSnapshot config = tracer.captureTraceConfig();
      assertEquals(expectedValue, config.getTracingTags());
      assertEquals(expectedValue, config.mergedTracerTags);

      capturedUpdater[0].remove(key, null);
      capturedUpdater[0].commit(null);

      assertEquals(Collections.emptyMap(), tracer.captureTraceConfig().getTracingTags());
    } finally {
      tracer.close();
    }
  }

  static Stream<Object[]> verifyConfigurationPollingWithCustomTagsArguments() {
    return Stream.of(
        new Object[] {
          "a:b c:d e:f",
          "{\"lib_config\":{\"tracing_tags\": [\"a:b\", \"c:d\", \"e:f\"]}}",
          buildStringMap("a", "b", "c", "d", "e", "f")
        },
        new Object[] {
          "empty and c:d",
          "{\"lib_config\":{\"tracing_tags\": [\"\", \"c:d\", \"\"]}}",
          buildStringMap("c", "d")
        },
        new Object[] {
          ":b c: e:f",
          "{\"lib_config\":{\"tracing_tags\": [\":b\", \"c:\", \"e:f\"]}}",
          buildStringMap("e", "f")
        },
        new Object[] {
          ": c: e:f",
          "{\"lib_config\":{\"tracing_tags\": [\":\", \"c:\", \"e:f\"]}}",
          buildStringMap("e", "f")
        },
        new Object[] {
          ": c: empty",
          "{\"lib_config\":{\"tracing_tags\": [\":\", \"c:\", \"\"]}}",
          Collections.emptyMap()
        },
        new Object[] {
          "empty array", "{\"lib_config\":{\"tracing_tags\": []}}", Collections.emptyMap()
        });
  }

  @ParameterizedTest
  @MethodSource("verifyConfigurationPollingWithTracingEnabledArguments")
  void verifyConfigurationPollingWithTracingEnabled(
      String scenario, String json, boolean expectedValue) throws Exception {
    ParsedConfigKey key = ParsedConfigKey.parse("datadog/2/APM_TRACING/config_overrides/config");
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    SharedCommunicationObjects sco = createScoWithPoller(poller);

    ProductListener[] capturedUpdater = {null};
    doAnswer(
            inv -> {
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
      assertTrue(tracer.captureTraceConfig().isTraceEnabled());

      capturedUpdater[0].accept(key, json.getBytes(StandardCharsets.UTF_8), null);
      capturedUpdater[0].commit(null);

      assertEquals(expectedValue, tracer.captureTraceConfig().isTraceEnabled());
    } finally {
      tracer.close();
    }
  }

  static Stream<Object[]> verifyConfigurationPollingWithTracingEnabledArguments() {
    return Stream.of(
        new Object[] {"tracing disabled", "{\"lib_config\":{\"tracing_enabled\": false}}", false},
        new Object[] {"tracing enabled", "{\"lib_config\":{\"tracing_enabled\": true}}", true},
        new Object[] {
          "action with tracing disabled",
          "{\"action\": \"enable\", \"lib_config\": {\"tracing_sampling_rate\": null,"
              + " \"log_injection_enabled\": null, \"tracing_header_tags\": null,"
              + " \"runtime_metrics_enabled\": null, \"tracing_debug\": null,"
              + " \"tracing_service_mapping\": null, \"tracing_sampling_rules\": null,"
              + " \"span_sampling_rules\": null, \"data_streams_enabled\": null,"
              + " \"tracing_enabled\": false}}",
          false
        });
  }

  @TableTest({
    "scenario  | preferred | expected",
    "no pref   |           | test    ",
    "with pref | some      | some    "
  })
  void testLocalRootServiceNameOverride(String preferred, String expected) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).serviceName("test").build();
    tracer.updatePreferredServiceName(preferred, preferred);
    try {
      DDSpan span = (DDSpan) tracer.startSpan("", "test");
      span.finish();
      assertEquals(expected, span.getServiceName());
      if (preferred != null) {
        assertTrue(ServiceNameCollector.get().getServices().contains(preferred));
      }
    } finally {
      tracer.close();
    }
  }

  @Test
  @WithConfig(key = GeneralConfig.SERVICE_NAME, value = "dd_service_name")
  @WithConfig(key = GeneralConfig.VERSION, value = "1.0.0")
  void testDdVersionExistsOnlyIfServiceEqDdService() {
    TagsPostProcessorFactory.withAddInternalTags(true);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span =
          (DDSpan) tracer.buildSpan("def").withTag(GeneralConfig.SERVICE_NAME, "foo").start();
      span.finish();
      assertEquals("foo", span.getServiceName());
      assertFalse(span.getTags().containsKey("version"));

      DDSpan span2 = (DDSpan) tracer.buildSpan("abc").start();
      span2.finish();
      assertEquals("dd_service_name", span2.getServiceName());
      assertEquals("1.0.0", String.valueOf(span2.getTags().get("version")));
    } finally {
      tracer.close();
    }
  }

  @Test
  void flushesOnTracerCloseIfConfiguredToDoSo() {
    WriterWithExplicitFlush writer = new WriterWithExplicitFlush();
    CoreTracer tracer = tracerBuilder().writer(writer).flushOnClose(true).build();
    tracer.buildSpan("my_span").start().finish();
    tracer.close();
    assertFalse(writer.flushedTraces.isEmpty());
  }

  @TableTest({
    "scenario                    | service | env | targetService | targetEnv",
    "diff target service         | service | env | service_1     | env      ",
    "diff target env             | service | env | service       | env_1    ",
    "diff target service and env | service | env | service_2     | env_2    "
  })
  void verifyNoFilteringOfServiceEnvWhenMismatchedWithDdServiceDdEnv(
      String service, String env, String targetService, String targetEnv) throws Exception {
    injectSysConfig(GeneralConfig.SERVICE_NAME, service);
    injectSysConfig(GeneralConfig.ENV, env);

    ParsedConfigKey key = ParsedConfigKey.parse("datadog/2/APM_TRACING/config_overrides/config");
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    SharedCommunicationObjects sco = createScoWithPoller(poller);

    ProductListener[] capturedUpdater = {null};
    doAnswer(
            inv -> {
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

      String json =
          String.format(
              "{\"service_target\":{\"service\":\"%s\",\"env\":\"%s\"},"
                  + "\"lib_config\":{\"tracing_service_mapping\":"
                  + "[{\"from_key\":\"foobar\",\"to_name\":\"bar\"}]}}",
              targetService, targetEnv);

      capturedUpdater[0].accept(key, json.getBytes(StandardCharsets.UTF_8), null);
      capturedUpdater[0].commit(null);

      assertEquals(
          buildStringMap("foobar", "bar"), tracer.captureTraceConfig().getServiceMapping());
    } finally {
      tracer.close();
    }
  }

  @Test
  void serviceNameSourceIsRecordedWhenUsingTwoParameterSetServiceName() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("operation").start();
      span.setServiceName("custom-service", "my-integration");
      DDSpan child = (DDSpan) tracer.buildSpan("child").start();
      child.finish();
      span.finish();

      assertEquals("custom-service", span.getServiceName());
      assertEquals("my-integration", span.getTag(DDTags.DD_SVC_SRC));
    } finally {
      tracer.close();
    }
  }

  @Test
  void serviceNameSourceIsMarkedAsManualWhenUsingOneParameterSetServiceName() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("operation").start();
      span.setServiceName("custom-service", "my-integration");
      span.setServiceName("another");
      span.finish();

      assertEquals("another", span.getServiceName());
      assertEquals(ServiceNameSources.MANUAL, span.getTag(DDTags.DD_SVC_SRC));
    } finally {
      tracer.close();
    }
  }

  @Test
  void serviceNameSourceIsMissingWhenNotExplicitlySettingServiceName() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("operation").start();
      span.finish();

      assertEquals(tracer.serviceName, span.getServiceName());
      assertNull(span.getTag(DDTags.DD_SVC_SRC));
    } finally {
      tracer.close();
    }
  }

  // --- helpers ---

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

  private static Map<String, String> buildStringMap(String... keyValues) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  // --- inner classes ---

  static class WriterWithExplicitFlush implements datadog.trace.common.writer.Writer {
    final List<List<DDSpan>> writtenTraces = new CopyOnWriteArrayList<>();
    final List<List<DDSpan>> flushedTraces = new CopyOnWriteArrayList<>();

    @Override
    public void write(List<DDSpan> trace) {
      writtenTraces.add(trace);
    }

    @Override
    public void start() {}

    @Override
    public boolean flush() {
      flushedTraces.addAll(writtenTraces);
      writtenTraces.clear();
      return true;
    }

    @Override
    public void close() {}

    @Override
    public void incrementDropCounts(int spanCount) {}
  }
}
