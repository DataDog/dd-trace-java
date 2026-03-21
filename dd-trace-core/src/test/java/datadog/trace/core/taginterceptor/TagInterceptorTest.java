package datadog.trace.core.taginterceptor;

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME;
import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.sampling.AllSampler;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TagInterceptorTest extends DDCoreSpecification {

  @BeforeEach
  void setup() {
    injectSysConfig(SPLIT_BY_TAGS, "sn.tag1,sn.tag2");
  }

  static Stream<Arguments> setServiceNameArguments() {
    Map<String, String> mapping = new HashMap<>();
    mapping.put("some-service", "new-service");
    return Stream.of(
        Arguments.of(DDTags.SERVICE_NAME, "some-service", "new-service", mapping),
        Arguments.of(DDTags.SERVICE_NAME, "other-service", "other-service", mapping),
        Arguments.of("service", "some-service", "new-service", mapping),
        Arguments.of("service", "other-service", "other-service", mapping),
        Arguments.of(Tags.PEER_SERVICE, "some-service", "new-service", mapping),
        Arguments.of(Tags.PEER_SERVICE, "other-service", "other-service", mapping),
        Arguments.of("sn.tag1", "some-service", "new-service", mapping),
        Arguments.of("sn.tag1", "other-service", "other-service", mapping),
        Arguments.of("sn.tag2", "some-service", "new-service", mapping),
        Arguments.of("sn.tag2", "other-service", "other-service", mapping));
  }

  @ParameterizedTest
  @MethodSource("setServiceNameArguments")
  void setServiceName(String tag, String name, String expected, Map<String, String> mapping)
      throws Exception {
    injectSysConfig("dd.trace.PeerServiceTagInterceptor.enabled", "true");
    datadog.trace.core.CoreTracer tracer =
        tracerBuilder()
            .serviceName("wrong-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .serviceNameMappings(mapping)
            .build();

    AgentSpan span = tracer.buildSpan("some span").withTag(tag, name).start();
    span.finish();

    assertEquals(expected, span.getServiceName());
    tracer.close();
  }

  static Stream<Arguments> defaultOrConfiguredServiceNameCanBeRemappedArguments() {
    Map<String, String> noMapping = new HashMap<>();
    noMapping.put("other-service-name", "other-service");
    Map<String, String> defaultMapping = new HashMap<>();
    defaultMapping.put(DEFAULT_SERVICE_NAME, "new-service");
    Map<String, String> otherMapping = new HashMap<>();
    otherMapping.put("other-service-name", "other-service");
    return Stream.of(
        Arguments.of(DEFAULT_SERVICE_NAME, DEFAULT_SERVICE_NAME, noMapping),
        Arguments.of(DEFAULT_SERVICE_NAME, "new-service", defaultMapping),
        Arguments.of("other-service-name", "other-service", otherMapping));
  }

  @ParameterizedTest
  @MethodSource("defaultOrConfiguredServiceNameCanBeRemappedArguments")
  void defaultOrConfiguredServiceNameCanBeRemappedWithoutSettingTag(
      String serviceName, String expected, Map<String, String> mapping) throws Exception {
    datadog.trace.core.CoreTracer tracer =
        tracerBuilder()
            .serviceName(serviceName)
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .serviceNameMappings(mapping)
            .build();

    AgentSpan span = tracer.buildSpan("some span").start();
    span.finish();

    assertEquals(expected, span.getServiceName());
    tracer.close();
  }

  static Stream<Arguments> setServiceNameFromServletContextArguments() {
    return Stream.of(
        Arguments.of(
            "/",
            DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME,
            DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME),
        Arguments.of("", DEFAULT_SERVICE_NAME, DEFAULT_SERVICE_NAME),
        Arguments.of("/some-context", DEFAULT_SERVICE_NAME, "some-context"),
        Arguments.of("other-context", DEFAULT_SERVICE_NAME, "other-context"),
        Arguments.of("/", "my-service", "my-service"),
        Arguments.of("", "my-service", "my-service"),
        Arguments.of("/some-context", "my-service", "my-service"),
        Arguments.of("other-context", "my-service", "my-service"));
  }

  @ParameterizedTest
  @MethodSource("setServiceNameFromServletContextArguments")
  void setServiceNameFromServletContext(String context, String serviceName, String expected)
      throws Exception {
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("test").start();
    span.setTag(DDTags.SERVICE_NAME, serviceName);
    span.setTag("servlet.context", context);

    assertEquals(expected, span.getServiceName());
    tracer.close();
  }

  static Stream<Arguments> settingServiceNameAsPropertyDisablesServletContextArguments() {
    String capturedServiceName =
        CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME);
    return Stream.of(
        Arguments.of("/", DEFAULT_SERVICE_NAME),
        Arguments.of("", DEFAULT_SERVICE_NAME),
        Arguments.of("/some-context", DEFAULT_SERVICE_NAME),
        Arguments.of("other-context", DEFAULT_SERVICE_NAME),
        Arguments.of("/", capturedServiceName),
        Arguments.of("", capturedServiceName),
        Arguments.of("/some-context", capturedServiceName),
        Arguments.of("other-context", capturedServiceName),
        Arguments.of("/", "my-service"),
        Arguments.of("", "my-service"),
        Arguments.of("/some-context", "my-service"),
        Arguments.of("other-context", "my-service"));
  }

  @ParameterizedTest
  @MethodSource("settingServiceNameAsPropertyDisablesServletContextArguments")
  void settingServiceNameAsPropertyDisablesServletContext(String context, String serviceName)
      throws Exception {
    injectSysConfig("service", serviceName);
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("test").start();
    span.setTag("servlet.context", context);

    assertEquals(serviceName, span.getServiceName());
    tracer.close();
  }

  static Stream<Arguments> mappingCausesServletContextToNotChangeServiceNameArguments() {
    Map<String, String> defaultMapping = new HashMap<>();
    defaultMapping.put(DEFAULT_SERVICE_NAME, "new-service");
    Map<String, String> myMapping = new HashMap<>();
    myMapping.put("my-service", "new-service");
    return Stream.of(
        Arguments.of("/some-context", DEFAULT_SERVICE_NAME, defaultMapping),
        Arguments.of("/some-context", "my-service", myMapping));
  }

  @ParameterizedTest
  @MethodSource("mappingCausesServletContextToNotChangeServiceNameArguments")
  void mappingCausesServletContextToNotChangeServiceName(
      String context, String serviceName, Map<String, String> mapping) throws Exception {
    datadog.trace.core.CoreTracer tracer =
        tracerBuilder()
            .serviceName(serviceName)
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .serviceNameMappings(mapping)
            .build();

    AgentSpan span = tracer.buildSpan("some span").start();
    span.setTag("servlet.context", context);
    span.finish();

    assertEquals("new-service", span.getServiceName());
    tracer.close();
  }

  private datadog.trace.core.CoreTracer createSplittingTracer(String tag) {
    return tracerBuilder()
        .serviceName("my-service")
        .writer(new LoggingWriter())
        .sampler(new AllSampler())
        .tagInterceptor(
            new TagInterceptor(
                true, "my-service", Collections.singleton(tag), new RuleFlags(), false))
        .build();
  }

  static Stream<Arguments> splitByTagsForServletContextArguments() {
    return Stream.of(Arguments.of("some-context", false), Arguments.of("my-service", true));
  }

  @ParameterizedTest
  @MethodSource("splitByTagsForServletContextArguments")
  void splitByTagsForServletContextAndExperimentalJeeSplitByDeployment(
      String expected, boolean jeeActive) throws Exception {
    datadog.trace.core.CoreTracer tracer =
        tracerBuilder()
            .serviceName("my-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .tagInterceptor(
                new TagInterceptor(
                    false, "my-service", Collections.emptySet(), new RuleFlags(), jeeActive))
            .build();

    AgentSpan span = tracer.buildSpan("some span").start();
    span.setTag(InstrumentationTags.SERVLET_CONTEXT, "some-context");
    span.finish();

    assertEquals(expected, span.getServiceName());
    tracer.close();
  }

  @Test
  void peerServiceThenSplitByTagsViaBuilder() throws Exception {
    datadog.trace.core.CoreTracer tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION);

    AgentSpan span =
        tracer
            .buildSpan("some span")
            .withTag(Tags.PEER_SERVICE, "peer-service")
            .withTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
            .start();
    span.finish();

    assertEquals("some-queue", span.getServiceName());
    tracer.close();
  }

  @Test
  void peerServiceThenSplitByTagsViaSetTag() throws Exception {
    datadog.trace.core.CoreTracer tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION);

    AgentSpan span = tracer.buildSpan("some span").start();
    span.setTag(Tags.PEER_SERVICE, "peer-service");
    span.setTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue");
    span.finish();

    assertEquals("some-queue", span.getServiceName());
    tracer.close();
  }

  static Stream<Arguments> splitByTagsThenPeerServiceViaBuilderArguments() {
    return Stream.of(Arguments.of(true), Arguments.of(false));
  }

  @ParameterizedTest
  @MethodSource("splitByTagsThenPeerServiceViaBuilderArguments")
  void splitByTagsThenPeerServiceViaBuilder(boolean enabled) throws Exception {
    injectSysConfig("dd.trace.PeerServiceTagInterceptor.enabled", String.valueOf(enabled));
    datadog.trace.core.CoreTracer tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION);

    AgentSpan span =
        tracer
            .buildSpan("some span")
            .withTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
            .withTag(Tags.PEER_SERVICE, "peer-service")
            .start();
    span.finish();

    assertEquals(enabled, span.getServiceName().equals("peer-service"));
    tracer.close();
  }

  @Test
  void splitByTagsThenPeerServiceViaSetTag() throws Exception {
    injectSysConfig("dd.trace.PeerServiceTagInterceptor.enabled", "true");
    datadog.trace.core.CoreTracer tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION);

    AgentSpan span = tracer.buildSpan("some span").start();
    span.setTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue");
    span.setTag(Tags.PEER_SERVICE, "peer-service");
    span.finish();

    assertEquals("peer-service", span.getServiceName());
    tracer.close();
  }

  @Test
  void setResourceName() throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();

    AgentSpan span = tracer.buildSpan("test").start();
    span.setTag(DDTags.RESOURCE_NAME, "my resource name");
    span.finish();
    writer.waitForTraces(1);

    assertEquals("my resource name", span.getResourceName().toString());
    tracer.close();
  }

  @Test
  void setResourceNameIgnoresNull() throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();

    AgentSpan span = tracer.buildSpan("test").withResourceName("keep").start();
    span.setTag(DDTags.RESOURCE_NAME, (String) null);
    span.finish();
    writer.waitForTraces(1);

    assertEquals("keep", span.getResourceName().toString());
    tracer.close();
  }

  @Test
  void setSpanType() throws Exception {
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("test").start();
    span.setSpanType(DDSpanTypes.HTTP_CLIENT);
    span.finish();

    assertEquals(DDSpanTypes.HTTP_CLIENT, span.getSpanType().toString());
    tracer.close();
  }

  @Test
  void setSpanTypeWithTag() throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();
    AgentSpan span = tracer.buildSpan("test").start();
    span.setTag(DDTags.SPAN_TYPE, DDSpanTypes.HTTP_CLIENT);
    span.finish();
    writer.waitForTraces(1);

    assertEquals(DDSpanTypes.HTTP_CLIENT, span.getSpanType().toString());
    tracer.close();
  }

  static Stream<Arguments> spanMetricsRateLimitingArguments() {
    return Stream.of(
        Arguments.of(0, 0),
        Arguments.of(1, 1),
        Arguments.of(0.0f, 0),
        Arguments.of(1.0f, 1),
        Arguments.of(0.1, 0.1),
        Arguments.of(1.1, 1.1),
        Arguments.of(-1, -1),
        Arguments.of(10, 10),
        Arguments.of("00", 0),
        Arguments.of("1", 1),
        Arguments.of("1.0", 1),
        Arguments.of("0", 0),
        Arguments.of("0.1", 0.1),
        Arguments.of("1.1", 1.1),
        Arguments.of("-1", -1),
        Arguments.of("str", null));
  }

  @ParameterizedTest
  @MethodSource("spanMetricsRateLimitingArguments")
  void spanMetricsStartsEmptyButAddedWithRateLimitingValue(Object rate, Object result)
      throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();
    AgentSpan span = tracer.buildSpan("test").start();

    assertNull(span.getTag(ANALYTICS_SAMPLE_RATE));

    span.setTag(ANALYTICS_SAMPLE_RATE, rate);
    span.finish();
    writer.waitForTraces(1);

    if (result == null) {
      assertNull(span.getTag(ANALYTICS_SAMPLE_RATE));
    } else if (result instanceof Number) {
      Object actual = span.getTag(ANALYTICS_SAMPLE_RATE);
      assertEquals(((Number) result).doubleValue(), ((Number) actual).doubleValue(), 0.001);
    } else {
      assertEquals(result, span.getTag(ANALYTICS_SAMPLE_RATE));
    }
    tracer.close();
  }

  static Stream<Arguments> setPrioritySamplingViaTagArguments() {
    return Stream.of(
        Arguments.of(DDTags.MANUAL_KEEP, true, (int) PrioritySampling.USER_KEEP),
        Arguments.of(DDTags.MANUAL_KEEP, false, null),
        Arguments.of(DDTags.MANUAL_KEEP, "true", (int) PrioritySampling.USER_KEEP),
        Arguments.of(DDTags.MANUAL_KEEP, "false", null),
        Arguments.of(DDTags.MANUAL_KEEP, "asdf", null),
        Arguments.of(DDTags.MANUAL_DROP, true, (int) PrioritySampling.USER_DROP),
        Arguments.of(DDTags.MANUAL_DROP, false, null),
        Arguments.of(DDTags.MANUAL_DROP, "true", (int) PrioritySampling.USER_DROP),
        Arguments.of(DDTags.MANUAL_DROP, "false", null),
        Arguments.of(DDTags.MANUAL_DROP, "asdf", null),
        Arguments.of(Tags.ASM_KEEP, true, (int) PrioritySampling.USER_KEEP),
        Arguments.of(Tags.ASM_KEEP, false, null),
        Arguments.of(Tags.ASM_KEEP, "true", (int) PrioritySampling.USER_KEEP),
        Arguments.of(Tags.ASM_KEEP, "false", null),
        Arguments.of(Tags.ASM_KEEP, "asdf", null),
        Arguments.of(Tags.SAMPLING_PRIORITY, -1, (int) PrioritySampling.USER_DROP),
        Arguments.of(Tags.SAMPLING_PRIORITY, 0, (int) PrioritySampling.USER_DROP),
        Arguments.of(Tags.SAMPLING_PRIORITY, 1, (int) PrioritySampling.USER_KEEP),
        Arguments.of(Tags.SAMPLING_PRIORITY, 2, (int) PrioritySampling.USER_KEEP),
        Arguments.of(Tags.SAMPLING_PRIORITY, "-1", (int) PrioritySampling.USER_DROP),
        Arguments.of(Tags.SAMPLING_PRIORITY, "0", (int) PrioritySampling.USER_DROP),
        Arguments.of(Tags.SAMPLING_PRIORITY, "1", (int) PrioritySampling.USER_KEEP),
        Arguments.of(Tags.SAMPLING_PRIORITY, "2", (int) PrioritySampling.USER_KEEP),
        Arguments.of(Tags.SAMPLING_PRIORITY, "asdf", null));
  }

  @ParameterizedTest
  @MethodSource("setPrioritySamplingViaTagArguments")
  void setPrioritySamplingViaTag(String tag, Object value, Integer expected) throws Exception {
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("test").start();
    span.setTag(tag, value);

    assertEquals(expected, span.getSamplingPriority());
    tracer.close();
  }

  static Stream<Arguments> setErrorFlagArguments() {
    return Stream.of(Arguments.of(true), Arguments.of(false));
  }

  @ParameterizedTest
  @MethodSource("setErrorFlagArguments")
  void setErrorFlagWhenErrorTagReported(boolean error) throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();
    AgentSpan span = tracer.buildSpan("test").start();
    span.setTag(Tags.ERROR, error);
    span.finish();
    writer.waitForTraces(1);

    assertEquals(error, span.isError());
    tracer.close();
  }

  static Stream<Arguments> attributeInterceptorsApplyToBuilderArguments() {
    return Stream.of(
        Arguments.of("serviceName", DDTags.SERVICE_NAME, "my-service"),
        Arguments.of("resourceName", DDTags.RESOURCE_NAME, "my-resource"),
        Arguments.of("spanType", DDTags.SPAN_TYPE, "my-span-type"));
  }

  @ParameterizedTest
  @MethodSource("attributeInterceptorsApplyToBuilderArguments")
  void attributeInterceptorsApplyToBuilder(String attribute, String name, String value)
      throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();

    AgentSpan span = tracer.buildSpan("interceptor.test").withTag(name, value).start();
    span.finish();
    writer.waitForTraces(1);

    DDSpanContext ctx = (DDSpanContext) span.context();
    switch (attribute) {
      case "serviceName":
        assertEquals(value, ctx.getServiceName());
        break;
      case "resourceName":
        assertEquals(value, ctx.getResourceName().toString());
        break;
      case "spanType":
        assertEquals(value, ctx.getSpanType().toString());
        break;
    }
    tracer.close();
  }

  @Test
  void decoratorsApplyToBuilderToo() throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();

    AgentSpan span = tracer.buildSpan("decorator.test").withTag("sn.tag1", "some val").start();
    span.finish();
    writer.waitForTraces(1);
    assertEquals("some val", span.getServiceName());

    span = tracer.buildSpan("decorator.test").withTag("servlet.context", "/my-servlet").start();
    assertEquals("my-servlet", span.getServiceName());

    span = tracer.buildSpan("decorator.test").withTag("error", "true").start();
    span.finish();
    writer.waitForTraces(2);
    assertTrue(span.isError());

    span = tracer.buildSpan("decorator.test").withTag(Tags.DB_STATEMENT, "some-statement").start();
    span.finish();
    writer.waitForTraces(3);
    assertEquals("some-statement", span.getResourceName().toString());

    tracer.close();
  }

  static Stream<Arguments> disableDecoratorViaConfigArguments() {
    return Stream.of(
        Arguments.of("servicenametaginterceptor", true),
        Arguments.of("ServiceNameTagInterceptor", true),
        Arguments.of("servicenametaginterceptor", false),
        Arguments.of("ServiceNameTagInterceptor", false));
  }

  @ParameterizedTest
  @MethodSource("disableDecoratorViaConfigArguments")
  void disableDecoratorViaConfig(String decorator, boolean enabled) throws Exception {
    injectSysConfig("dd.trace." + decorator + ".enabled", String.valueOf(enabled));

    datadog.trace.core.CoreTracer tracer =
        tracerBuilder()
            .serviceName("some-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .build();

    AgentSpan span =
        tracer.buildSpan("some span").withTag(DDTags.SERVICE_NAME, "other-service").start();
    span.finish();

    assertEquals(enabled ? "other-service" : "some-service", span.getServiceName());
    tracer.close();
  }

  static Stream<Arguments> disablingServiceDecoratorDoesNotDisableSplitByTagsArguments() {
    return Stream.of(
        Arguments.of(DDTags.SERVICE_NAME, "new-service", "some-service"),
        Arguments.of("service", "new-service", "some-service"),
        Arguments.of("sn.tag1", "new-service", "new-service"));
  }

  @ParameterizedTest
  @MethodSource("disablingServiceDecoratorDoesNotDisableSplitByTagsArguments")
  void disablingServiceDecoratorDoesNotDisableSplitByTags(String tag, String name, String expected)
      throws Exception {
    injectSysConfig("dd.trace.ServiceNameTagInterceptor.enabled", "false");

    datadog.trace.core.CoreTracer tracer =
        tracerBuilder()
            .serviceName("some-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .build();

    AgentSpan span = tracer.buildSpan("some span").withTag(tag, name).start();
    span.finish();

    assertEquals(expected, span.getServiceName());
    tracer.close();
  }

  @Test
  void changeTopLevelStatusWhenChangingServiceName() throws Exception {
    datadog.trace.core.CoreTracer tracer =
        tracerBuilder()
            .serviceName("some-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .build();

    AgentSpan parent = tracer.buildSpan("parent").withServiceName("parent").start();

    AgentSpan child =
        tracer.buildSpan("child").withServiceName("child").asChildOf(parent.context()).start();

    assertTrue(((CoreSpan) child).isTopLevel());

    child.setTag(DDTags.SERVICE_NAME, "parent");
    assertFalse(((CoreSpan) child).isTopLevel());

    child.setTag(DDTags.SERVICE_NAME, "foo");
    assertTrue(((CoreSpan) child).isTopLevel());

    tracer.close();
  }

  static Stream<Arguments> treatOneValueAsTrueForBooleanTagValuesArguments() {
    return Stream.of(
        Arguments.of(DDTags.MANUAL_DROP, true, (int) PrioritySampling.USER_DROP),
        Arguments.of(DDTags.MANUAL_DROP, "1", (int) PrioritySampling.USER_DROP),
        Arguments.of(DDTags.MANUAL_DROP, false, null),
        Arguments.of(DDTags.MANUAL_DROP, "0", null),
        Arguments.of(DDTags.MANUAL_KEEP, true, (int) PrioritySampling.USER_KEEP),
        Arguments.of(DDTags.MANUAL_KEEP, "1", (int) PrioritySampling.USER_KEEP),
        Arguments.of(DDTags.MANUAL_KEEP, false, null),
        Arguments.of(DDTags.MANUAL_KEEP, "0", null));
  }

  @ParameterizedTest
  @MethodSource("treatOneValueAsTrueForBooleanTagValuesArguments")
  void treatOneValueAsTrueForBooleanTagValues(String tag, Object value, Integer samplingPriority)
      throws Exception {
    datadog.trace.core.CoreTracer tracer =
        tracerBuilder()
            .serviceName("some-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .build();

    AgentSpan span = tracer.buildSpan("test").start();
    assertNull(span.getSamplingPriority());

    span.setTag(tag, value);
    assertEquals(samplingPriority, span.getSamplingPriority());
    tracer.close();
  }

  static Stream<Arguments> urlAsResourceNameRuleArguments() {
    return Stream.of(
        Arguments.of(null, "fakeOperation", Collections.emptyMap()),
        Arguments.of(" ", "/", Collections.emptyMap()),
        Arguments.of("\t", "/", Collections.emptyMap()),
        Arguments.of("/path", "/path", Collections.emptyMap()),
        Arguments.of("/ABC/a-1/b_2/c.3/d4d/5f/6", "/ABC/?/?/?/?/?/?", Collections.emptyMap()),
        Arguments.of("/not-found", "404", Collections.singletonMap(Tags.HTTP_STATUS, "404")),
        Arguments.of(
            "/with-method",
            "POST /with-method",
            Collections.singletonMap(Tags.HTTP_METHOD, "Post")));
  }

  @ParameterizedTest
  @MethodSource("urlAsResourceNameRuleArguments")
  void urlAsResourceNameRuleSetsTheResourceName(
      String value, String resourceName, Map<String, String> meta) throws Exception {
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan span = tracer.buildSpan("fakeOperation").start();
    for (Map.Entry<String, String> entry : meta.entrySet()) {
      span.setTag(entry.getKey(), entry.getValue());
    }
    span.setTag(Tags.HTTP_URL, value);

    assertEquals(resourceName, span.getResourceName().toString());

    span.finish();
    tracer.close();
  }

  @Test
  void whenUserSetsPeerServiceTheSourceShouldBePeerService() throws Exception {
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan span = tracer.buildSpan("fakeOperation").start();
    span.setTag(Tags.PEER_SERVICE, "test");

    assertEquals("peer.service", span.getTag(DDTags.PEER_SERVICE_SOURCE));

    span.finish();
    tracer.close();
  }

  @Test
  void whenInterceptServiceNameExtraServiceProviderIsCalled() {
    ServiceNameCollector extraServiceProvider = Mockito.mock(ServiceNameCollector.class);
    ServiceNameCollector.setForTesting(extraServiceProvider);
    RuleFlags ruleFlags = Mockito.mock(RuleFlags.class);
    Mockito.when(ruleFlags.isEnabled(Mockito.any())).thenReturn(true);
    TagInterceptor interceptor =
        new TagInterceptor(
            true, "my-service", Collections.singleton(DDTags.SERVICE_NAME), ruleFlags, false);

    interceptor.interceptServiceName(null, Mockito.mock(DDSpanContext.class), "some-service");

    Mockito.verify(extraServiceProvider).addService("some-service");
  }

  static Stream<Arguments> whenInterceptServletContextExtraServiceProviderIsCalledArguments() {
    return Stream.of(
        Arguments.of("/", "root-servlet"),
        Arguments.of("/test", "test"),
        Arguments.of("test", "test"));
  }

  @ParameterizedTest
  @MethodSource("whenInterceptServletContextExtraServiceProviderIsCalledArguments")
  void whenInterceptServletContextExtraServiceProviderIsCalled(String value, String expected) {
    ServiceNameCollector extraServiceProvider = Mockito.mock(ServiceNameCollector.class);
    ServiceNameCollector.setForTesting(extraServiceProvider);
    RuleFlags ruleFlags = Mockito.mock(RuleFlags.class);
    Mockito.when(ruleFlags.isEnabled(Mockito.any())).thenReturn(true);
    TagInterceptor interceptor =
        new TagInterceptor(
            true, "my-service", Collections.singleton("servlet.context"), ruleFlags, false);

    interceptor.interceptServletContext(Mockito.mock(DDSpanContext.class), value);

    Mockito.verify(extraServiceProvider).addService(expected);
  }

  @Test
  void whenInterceptsProductTraceSourcePropagationTagUpdatePropagatedTraceSourceIsCalled() {
    RuleFlags ruleFlags = Mockito.mock(RuleFlags.class);
    Mockito.when(ruleFlags.isEnabled(Mockito.any())).thenReturn(true);
    TagInterceptor interceptor = new TagInterceptor(ruleFlags);
    DDSpanContext context = Mockito.mock(DDSpanContext.class);

    interceptor.interceptTag(context, Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);

    Mockito.verify(context).addPropagatedTraceSource(ProductTraceSource.ASM);
  }
}
