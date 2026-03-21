package datadog.trace.core;

import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.config.TracerConfig.BAGGAGE_MAPPING;
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING;
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING;
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS;
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE;
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
import datadog.metrics.api.Monitoring;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.ServiceNameSources;
import datadog.trace.common.sampling.AllSampler;
import datadog.trace.common.sampling.RateByServiceTraceSampler;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory;
import datadog.trace.core.test.DDCoreSpecification;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(10)
class CoreTracerTest extends DDCoreSpecification {

  @Test
  void verifyDefaultsOnTracer() throws Exception {
    CoreTracer tracer = CoreTracer.builder().build();
    assertNotNull(tracer.serviceName);
    assertFalse(tracer.serviceName.isEmpty());
    assertInstanceOf(RateByServiceTraceSampler.class, tracer.initialSampler);
    assertInstanceOf(DDAgentWriter.class, tracer.writer);
    tracer.close();
  }

  @Test
  void verifyOverridingSampler() throws Exception {
    injectSysConfig(PRIORITY_SAMPLING, "false");
    CoreTracer tracer = tracerBuilder().build();
    assertInstanceOf(AllSampler.class, tracer.initialSampler);
    tracer.close();
  }

  @Test
  void verifyOverridingWriter() throws Exception {
    injectSysConfig(WRITER_TYPE, "LoggingWriter");
    CoreTracer tracer = tracerBuilder().build();
    assertInstanceOf(LoggingWriter.class, tracer.writer);
    tracer.close();
  }

  @Test
  void verifyUdsWindows() throws Exception {
    System.setProperty("os.name", "Windows ME");
    String uds = "asdf";
    injectSysConfig(AGENT_UNIX_DOMAIN_SOCKET, uds);
    assertEquals(uds, Config.get().getAgentUnixDomainSocket());
  }

  static Stream<Arguments> verifyMappingConfigsOnTracerArgs() {
    return Stream.of(
        Arguments.of("a:one, a:two, a:three", singletonMap("a", "three")),
        Arguments.of("a:b,c:d,e:", twoMap("a", "b", "c", "d")));
  }

  @ParameterizedTest
  @MethodSource("verifyMappingConfigsOnTracerArgs")
  void verifyMappingConfigsOnTracer(String mapString, Map<String, String> map) throws Exception {
    injectSysConfig(SERVICE_MAPPING, mapString);
    injectSysConfig(SPAN_TAGS, mapString);
    injectSysConfig(HEADER_TAGS, mapString);

    CoreTracer tracer = tracerBuilder().build();

    assertEquals(map, getDefaultSpanTags(tracer));
    assertEquals(map, tracer.captureTraceConfig().getServiceMapping());

    tracer.close();
  }

  static Stream<Arguments> verifyBaggageMappingConfigsOnTracerArgs() {
    return Stream.of(
        Arguments.of("a:one, a:two, a:three", singletonMap("a", "three")),
        Arguments.of("a:b,c:d,e:", twoMap("a", "b", "c", "d")));
  }

  @ParameterizedTest
  @MethodSource("verifyBaggageMappingConfigsOnTracerArgs")
  void verifyBaggageMappingConfigsOnTracer(String mapString, Map<String, String> map)
      throws Exception {
    injectSysConfig(BAGGAGE_MAPPING, mapString);

    CoreTracer tracer = tracerBuilder().build();

    assertEquals(map, tracer.captureTraceConfig().getBaggageMapping());

    tracer.close();
  }

  @Test
  void verifyOverridingHost() throws Exception {
    String value = "somethingelse";
    injectSysConfig("agent.host", value);
    assertEquals(value, Config.get().getAgentHost());
  }

  static Stream<Arguments> verifyOverridingPortArgs() {
    return Stream.of(Arguments.of("agent.port", "777"), Arguments.of("trace.agent.port", "9999"));
  }

  @ParameterizedTest
  @MethodSource("verifyOverridingPortArgs")
  void verifyOverridingPort(String key, String value) throws Exception {
    injectSysConfig(key, value);
    assertEquals(Integer.valueOf(value), Config.get().getAgentPort());
  }

  @Test
  void writerIsInstanceOfLoggingWriterWhenPropertySet() throws Exception {
    injectSysConfig("writer.type", "LoggingWriter");
    CoreTracer tracer = tracerBuilder().build();
    assertInstanceOf(LoggingWriter.class, tracer.writer);
    tracer.close();
  }

  static Stream<Arguments> sharesTraceCountWithDDApiArgs() {
    return Stream.of(
        Arguments.of(PRIORITY_SAMPLING, "true"), Arguments.of(PRIORITY_SAMPLING, "false"));
  }

  @ParameterizedTest
  @MethodSource("sharesTraceCountWithDDApiArgs")
  void sharesTraceCountWithDDApi(String key, String value) throws Exception {
    injectSysConfig(key, value);
    CoreTracer tracer = tracerBuilder().build();
    assertInstanceOf(DDAgentWriter.class, tracer.writer);
    tracer.close();
  }

  @Test
  void rootTagsAreAppliedOnlyToRootSpans() throws Exception {
    Map<String, Object> localRootSpanTags = new HashMap<>();
    localRootSpanTags.put("only_root", "value");
    CoreTracer tracer = tracerBuilder().localRootSpanTags(localRootSpanTags).build();
    DDSpan root = (DDSpan) tracer.buildSpan("my_root").start();
    DDSpan child = (DDSpan) tracer.buildSpan("my_child").asChildOf(root.context()).start();

    assertTrue(root.context().getTags().containsKey("only_root"));
    assertFalse(child.context().getTags().containsKey("only_root"));

    child.finish();
    root.finish();
    tracer.close();
  }

  @Test
  void prioritySamplingWhenSpanFinishes() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();

    DDSpan span = (DDSpan) tracer.buildSpan("operation").start();
    span.finish();
    writer.waitForTraces(1);

    assertEquals((int) PrioritySampling.SAMPLER_KEEP, (int) span.getSamplingPriority());

    tracer.close();
  }

  @Test
  void prioritySamplingSetWhenChildSpanComplete() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();

    DDSpan root = (DDSpan) tracer.buildSpan("operation").start();
    DDSpan child = (DDSpan) tracer.buildSpan("my_child").asChildOf(root.context()).start();
    root.finish();

    assertNull(root.getSamplingPriority());

    child.finish();
    writer.waitForTraces(1);

    assertEquals((int) PrioritySampling.SAMPLER_KEEP, (int) root.getSamplingPriority());
    assertEquals(root.getSamplingPriority(), child.getSamplingPriority());

    tracer.close();
  }

  @Test
  void verifyConfigurationPolling() throws Exception {
    ParsedConfigKey key = ParsedConfigKey.parse("datadog/2/APM_TRACING/config_overrides/config");
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

    verify(poller).addListener(eq(Product.APM_TRACING), any(ProductListener.class));
    assertNotNull(updaterRef.get());

    assertTrue(tracer.captureTraceConfig().getServiceMapping().isEmpty());
    assertTrue(tracer.captureTraceConfig().getRequestHeaderTags().isEmpty());
    assertTrue(tracer.captureTraceConfig().getResponseHeaderTags().isEmpty());
    assertNull(tracer.captureTraceConfig().getTraceSampleRate());

    ProductListener updater = updaterRef.get();
    updater.accept(
        key,
        ("{\n"
                + "  \"lib_config\": {\n"
                + "    \"tracing_service_mapping\": [\n"
                + "      {\"from_key\": \"foobar\", \"to_name\": \"bar\"},\n"
                + "      {\"from_key\": \"snafu\", \"to_name\": \"foo\"}\n"
                + "    ],\n"
                + "    \"tracing_header_tags\": [\n"
                + "      {\"header\": \"Cookie\", \"tag_name\": \"\"},\n"
                + "      {\"header\": \"Referer\", \"tag_name\": \"http.referer\"},\n"
                + "      {\"header\": \"  Some.Header  \", \"tag_name\": \"\"},\n"
                + "      {\"header\": \"C!!!ont_____ent----tYp!/!e\", \"tag_name\": \"\"},\n"
                + "      {\"header\": \"this.header\", \"tag_name\": \"whatever.the.user.wants.this.header\"}\n"
                + "    ],\n"
                + "    \"tracing_sampling_rate\": 0.5\n"
                + "  }\n"
                + "}")
            .getBytes(StandardCharsets.UTF_8),
        null);
    updater.commit(null);

    Map<String, String> expectedServiceMapping = new HashMap<>();
    expectedServiceMapping.put("foobar", "bar");
    expectedServiceMapping.put("snafu", "foo");
    assertEquals(expectedServiceMapping, tracer.captureTraceConfig().getServiceMapping());

    Map<String, String> expectedRequestHeaders = new HashMap<>();
    expectedRequestHeaders.put("cookie", "http.request.headers.cookie");
    expectedRequestHeaders.put("referer", "http.referer");
    expectedRequestHeaders.put("some.header", "http.request.headers.some_header");
    expectedRequestHeaders.put(
        "c!!!ont_____ent----typ!/!e", "http.request.headers.c___ont_____ent----typ_/_e");
    expectedRequestHeaders.put("this.header", "whatever.the.user.wants.this.header");
    assertEquals(expectedRequestHeaders, tracer.captureTraceConfig().getRequestHeaderTags());

    Map<String, String> expectedResponseHeaders = new HashMap<>();
    expectedResponseHeaders.put("cookie", "http.response.headers.cookie");
    expectedResponseHeaders.put("referer", "http.referer");
    expectedResponseHeaders.put("some.header", "http.response.headers.some_header");
    expectedResponseHeaders.put(
        "c!!!ont_____ent----typ!/!e", "http.response.headers.c___ont_____ent----typ_/_e");
    expectedResponseHeaders.put("this.header", "whatever.the.user.wants.this.header");
    assertEquals(expectedResponseHeaders, tracer.captureTraceConfig().getResponseHeaderTags());

    assertEquals(0.5, tracer.captureTraceConfig().getTraceSampleRate(), 0.001);

    updater.remove(key, null);
    updater.commit(null);

    assertTrue(tracer.captureTraceConfig().getServiceMapping().isEmpty());
    assertTrue(tracer.captureTraceConfig().getRequestHeaderTags().isEmpty());
    assertTrue(tracer.captureTraceConfig().getResponseHeaderTags().isEmpty());
    assertNull(tracer.captureTraceConfig().getTraceSampleRate());

    tracer.close();
  }

  static Stream<Arguments> verifyConfigurationPollingWithCustomTagsArgs() {
    return Stream.of(
        Arguments.of(
            "{\"lib_config\":{\"tracing_tags\": [\"a:b\", \"c:d\", \"e:f\"]}}",
            threeMap("a", "b", "c", "d", "e", "f")),
        Arguments.of(
            "{\"lib_config\":{\"tracing_tags\": [\"\", \"c:d\", \"\"]}}", singletonMap("c", "d")),
        Arguments.of(
            "{\"lib_config\":{\"tracing_tags\": [\":b\", \"c:\", \"e:f\"]}}",
            singletonMap("e", "f")),
        Arguments.of(
            "{\"lib_config\":{\"tracing_tags\": [\":\", \"c:\", \"e:f\"]}}",
            singletonMap("e", "f")),
        Arguments.of("{\"lib_config\":{\"tracing_tags\": [\":\", \"c:\", \"\"]}}", new HashMap<>()),
        Arguments.of("{\"lib_config\":{\"tracing_tags\": []}}", new HashMap<>()));
  }

  @ParameterizedTest
  @MethodSource("verifyConfigurationPollingWithCustomTagsArgs")
  void verifyConfigurationPollingWithCustomTags(String value, Map<String, String> expectedValue)
      throws Exception {
    ParsedConfigKey key = ParsedConfigKey.parse("datadog/2/APM_TRACING/config_overrides/config");
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

    assertTrue(tracer.captureTraceConfig().getTracingTags().isEmpty());

    ProductListener updater = updaterRef.get();
    updater.accept(key, value.getBytes(StandardCharsets.UTF_8), null);
    updater.commit(null);

    assertEquals(expectedValue, tracer.captureTraceConfig().getTracingTags());
    assertEquals(
        expectedValue, ((CoreTracer.ConfigSnapshot) tracer.captureTraceConfig()).mergedTracerTags);

    updater.remove(key, null);
    updater.commit(null);

    assertTrue(tracer.captureTraceConfig().getTracingTags().isEmpty());

    tracer.close();
  }

  static Stream<Arguments> verifyConfigurationPollingWithTracingEnabledArgs() {
    return Stream.of(
        Arguments.of("{\"lib_config\":{\"tracing_enabled\": false } } ", false),
        Arguments.of("{\"lib_config\":{\"tracing_enabled\": true } } ", true),
        Arguments.of(
            "{\"action\": \"enable\", \"lib_config\": {\"tracing_sampling_rate\": null, "
                + "\"log_injection_enabled\": null, \"tracing_header_tags\": null, "
                + "\"runtime_metrics_enabled\": null, \"tracing_debug\": null, "
                + "\"tracing_service_mapping\": null, \"tracing_sampling_rules\": null, "
                + "\"span_sampling_rules\": null, \"data_streams_enabled\": null, "
                + "\"tracing_enabled\": false}}",
            false));
  }

  @ParameterizedTest
  @MethodSource("verifyConfigurationPollingWithTracingEnabledArgs")
  void verifyConfigurationPollingWithTracingEnabled(String value, boolean expectedValue)
      throws Exception {
    ParsedConfigKey key = ParsedConfigKey.parse("datadog/2/APM_TRACING/config_overrides/config");
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

    ProductListener updater = updaterRef.get();
    updater.accept(key, value.getBytes(StandardCharsets.UTF_8), null);
    updater.commit(null);

    assertEquals(expectedValue, tracer.captureTraceConfig().isTraceEnabled());

    tracer.close();
  }

  static Stream<Arguments> testLocalRootServiceNameOverrideArgs() {
    return Stream.of(Arguments.of(null, "test"), Arguments.of("some", "some"));
  }

  @ParameterizedTest
  @MethodSource("testLocalRootServiceNameOverrideArgs")
  void testLocalRootServiceNameOverride(String preferred, String expected) throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).serviceName("test").build();
    tracer.updatePreferredServiceName(preferred, preferred);
    DDSpan span = (DDSpan) tracer.startSpan("", "test");
    span.finish();
    assertEquals(expected, span.getServiceName());
    if (preferred != null) {
      assertTrue(ServiceNameCollector.get().getServices().contains(preferred));
    }
    tracer.close();
  }

  @Test
  void testDdVersionExistsOnlyIfServiceEqualsDdService() throws Exception {
    injectSysConfig(SERVICE_NAME, "dd_service_name");
    injectSysConfig(VERSION, "1.0.0");
    TagsPostProcessorFactory.withAddInternalTags(true);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    DDSpan span = (DDSpan) tracer.buildSpan("def").withTag(SERVICE_NAME, "foo").start();
    span.finish();
    assertEquals("foo", span.getServiceName());
    assertFalse(span.getTags().containsKey("version"));

    DDSpan span2 = (DDSpan) tracer.buildSpan("abc").start();
    span2.finish();
    assertEquals("dd_service_name", span2.getServiceName());
    assertNotNull(span2.getTags().get("version"));
    assertEquals("1.0.0", span2.getTags().get("version").toString());

    tracer.close();
  }

  @Test
  void flushesOnTracerCloseIfConfiguredToDoSo() throws Exception {
    WriterWithExplicitFlush writer = new WriterWithExplicitFlush();
    CoreTracer tracer = tracerBuilder().writer(writer).flushOnClose(true).build();

    tracer.buildSpan("my_span").start().finish();
    tracer.close();

    assertFalse(writer.flushedTraces.isEmpty());
  }

  static Stream<Arguments> verifyNoFilteringOfServiceEnvWhenMismatchedArgs() {
    return Stream.of(
        Arguments.of("service", "env", "service_1", "env"),
        Arguments.of("service", "env", "service", "env_1"),
        Arguments.of("service", "env", "service_2", "env_2"));
  }

  @ParameterizedTest
  @MethodSource("verifyNoFilteringOfServiceEnvWhenMismatchedArgs")
  void verifyNoFilteringOfServiceEnvWhenMismatchedWithDdServiceDdEnv(
      String service, String env, String targetService, String targetEnv) throws Exception {
    injectSysConfig(SERVICE_NAME, service);
    injectSysConfig(ENV, env);

    ParsedConfigKey key = ParsedConfigKey.parse("datadog/2/APM_TRACING/config_overrides/config");
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

    ProductListener updater = updaterRef.get();
    updater.accept(
        key,
        ("{\n"
                + "  \"service_target\": {\"service\": \""
                + targetService
                + "\", \"env\": \""
                + targetEnv
                + "\"},\n"
                + "  \"lib_config\": {\n"
                + "    \"tracing_service_mapping\": [{\"from_key\": \"foobar\", \"to_name\": \"bar\"}]\n"
                + "  }\n"
                + "}")
            .getBytes(StandardCharsets.UTF_8),
        null);
    updater.commit(null);

    Map<String, String> expected = new HashMap<>();
    expected.put("foobar", "bar");
    assertEquals(expected, tracer.captureTraceConfig().getServiceMapping());

    tracer.close();
  }

  @Test
  void serviceNameSourceIsRecordedWhenUsingTwoParameterSetServiceName() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    DDSpan span = (DDSpan) tracer.buildSpan("operation").start();
    span.setServiceName("custom-service", "my-integration");
    DDSpan child = (DDSpan) tracer.buildSpan("child").start();
    child.finish();
    span.finish();

    assertEquals("custom-service", span.getServiceName());
    assertEquals("my-integration", span.getTag(DDTags.DD_SVC_SRC));

    tracer.close();
  }

  @Test
  void serviceNameSourceIsMarkedAsManualWhenUsingOneParameterSetServiceName() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    DDSpan span = (DDSpan) tracer.buildSpan("operation").start();
    span.setServiceName("custom-service", "my-integration");
    span.setServiceName("another");
    span.finish();

    assertEquals("another", span.getServiceName());
    assertEquals(ServiceNameSources.MANUAL, span.getTag(DDTags.DD_SVC_SRC));

    tracer.close();
  }

  @Test
  void serviceNameSourceIsMissingWhenNotExplicitlySettingTheServiceName() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    DDSpan span = (DDSpan) tracer.buildSpan("operation").start();
    span.finish();

    assertEquals(tracer.serviceName, span.getServiceName());
    assertNull(span.getTag(DDTags.DD_SVC_SRC));

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

  @SuppressWarnings("unchecked")
  static Map<String, Object> getDefaultSpanTags(CoreTracer tracer) throws Exception {
    java.lang.reflect.Field f = CoreTracer.class.getDeclaredField("defaultSpanTags");
    f.setAccessible(true);
    return (Map<String, Object>) f.get(tracer);
  }

  static Map<String, String> singletonMap(String key, String value) {
    Map<String, String> m = new HashMap<>();
    m.put(key, value);
    return m;
  }

  static Map<String, String> twoMap(String k1, String v1, String k2, String v2) {
    Map<String, String> m = new HashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    return m;
  }

  static Map<String, String> threeMap(
      String k1, String v1, String k2, String v2, String k3, String v3) {
    Map<String, String> m = new HashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    m.put(k3, v3);
    return m;
  }
}

class WriterWithExplicitFlush implements datadog.trace.common.writer.Writer {
  List<List<DDSpan>> writtenTraces = new CopyOnWriteArrayList<>();
  List<List<DDSpan>> flushedTraces = new CopyOnWriteArrayList<>();

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
