package datadog.trace.core.taginterceptor;

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.api.remoteconfig.ServiceNameCollectorTestBridge;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.sampling.AllSampler;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.junit.utils.tabletest.ConfigDefaultsConverter;
import datadog.trace.junit.utils.tabletest.DDTagsConverter;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@WithConfig(key = SPLIT_BY_TAGS, value = "sn.tag1,sn.tag2")
class TagInterceptorTest extends DDCoreJavaSpecification {

  @TableTest({
    "scenario           | tag                   | name            | expected       ",
    "service.name some  | 'DDTags.SERVICE_NAME' | 'some-service'  | 'new-service'  ",
    "service.name other | 'DDTags.SERVICE_NAME' | 'other-service' | 'other-service'",
    "service some       | 'service'             | 'some-service'  | 'new-service'  ",
    "service other      | 'service'             | 'other-service' | 'other-service'",
    "peer.service some  | 'Tags.PEER_SERVICE'   | 'some-service'  | 'new-service'  ",
    "peer.service other | 'Tags.PEER_SERVICE'   | 'other-service' | 'other-service'",
    "sn.tag1 some       | 'sn.tag1'             | 'some-service'  | 'new-service'  ",
    "sn.tag1 other      | 'sn.tag1'             | 'other-service' | 'other-service'",
    "sn.tag2 some       | 'sn.tag2'             | 'some-service'  | 'new-service'  ",
    "sn.tag2 other      | 'sn.tag2'             | 'other-service' | 'other-service'"
  })
  @WithConfig(key = "dd.trace.PeerServiceTagInterceptor.enabled", value = "true", addPrefix = false)
  void setServiceName(
      @ConvertWith(DDTagsConverter.class) String tag, String name, String expected) {
    Map<String, String> mapping = Collections.singletonMap("some-service", "new-service");
    CoreTracer tracer =
        tracerBuilder()
            .serviceName("wrong-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .serviceNameMappings(mapping)
            .build();

    AgentSpan span = tracer.buildSpan("datadog", "some span").withTag(tag, name).start();
    span.finish();

    assertEquals(expected, span.getServiceName());
  }

  @TableTest({
    "scenario                   | serviceName            | expected               | mapping                              ",
    "default service / no match | 'DEFAULT_SERVICE_NAME' | 'DEFAULT_SERVICE_NAME' | [other-service-name: other-service]  ",
    "default service / match    | 'DEFAULT_SERVICE_NAME' | 'new-service'          | ['DEFAULT_SERVICE_NAME': new-service]",
    "custom service / match     | 'other-service-name'   | 'other-service'        | [other-service-name: other-service]  "
  })
  void defaultOrConfiguredServiceNameCanBeRemappedWithoutSettingTag(
      @ConvertWith(ConfigDefaultsConverter.class) String serviceName,
      @ConvertWith(ConfigDefaultsConverter.class) String expected,
      @ConvertWith(ConfigDefaultsConverter.class) Map<String, String> mapping) {
    CoreTracer tracer =
        tracerBuilder()
            .serviceName(serviceName)
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .serviceNameMappings(mapping)
            .build();
    AgentSpan span = tracer.buildSpan("datadog", "some span").start();
    span.finish();

    assertEquals(expected, span.getServiceName());
  }

  @TableTest({
    "scenario                           | context         | serviceName                                 | expected                                   ",
    "root context with default service  | '/'             | 'DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME' | 'DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME'",
    "empty context with default service | ''              | 'DEFAULT_SERVICE_NAME'                      | 'DEFAULT_SERVICE_NAME'                     ",
    "/some-context with default service | '/some-context' | 'DEFAULT_SERVICE_NAME'                      | 'some-context'                             ",
    "other-context with default service | 'other-context' | 'DEFAULT_SERVICE_NAME'                      | 'other-context'                            ",
    "root context with my-service       | '/'             | 'my-service'                                | 'my-service'                               ",
    "empty context with my-service      | ''              | 'my-service'                                | 'my-service'                               ",
    "/some-context with my-service      | '/some-context' | 'my-service'                                | 'my-service'                               ",
    "other-context with my-service      | 'other-context' | 'my-service'                                | 'my-service'                               "
  })
  void setServiceNameFromServletContext(
      String context,
      @ConvertWith(ConfigDefaultsConverter.class) String serviceName,
      @ConvertWith(ConfigDefaultsConverter.class) String expected) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("datadog", "test").start();
    span.setTag(DDTags.SERVICE_NAME, serviceName);
    span.setTag("servlet.context", context);

    assertEquals(expected, span.getServiceName());
  }

  @TableTest({
    "scenario                        | context         | serviceName           ",
    "root context / default service  | '/'             | 'DEFAULT_SERVICE_NAME'",
    "empty context / default service | ''              | 'DEFAULT_SERVICE_NAME'",
    "/some-context / default service | '/some-context' | 'DEFAULT_SERVICE_NAME'",
    "other-context / default service | 'other-context' | 'DEFAULT_SERVICE_NAME'",
    "root context / env service      | '/'             | 'ENV_SERVICE_NAME'    ",
    "empty context / env service     | ''              | 'ENV_SERVICE_NAME'    ",
    "/some-context / env service     | '/some-context' | 'ENV_SERVICE_NAME'    ",
    "other-context / env service     | 'other-context' | 'ENV_SERVICE_NAME'    ",
    "root context / my-service       | '/'             | 'my-service'          ",
    "empty context / my-service      | ''              | 'my-service'          ",
    "/some-context / my-service      | '/some-context' | 'my-service'          ",
    "other-context / my-service      | 'other-context' | 'my-service'          "
  })
  void settingServiceNameAsPropertyDisablesServletContext(
      String context, @ConvertWith(ConfigDefaultsConverter.class) String serviceName) {
    injectSysConfig("service", serviceName);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("datadog", "test").start();
    span.setTag("servlet.context", context);

    assertEquals(serviceName, span.getServiceName());
  }

  @TableTest({
    "scenario        | context         | serviceName            | mapping                            ",
    "default service | '/some-context' | 'DEFAULT_SERVICE_NAME' | [DEFAULT_SERVICE_NAME: new-service]",
    "my-service      | '/some-context' | 'my-service'           | [my-service: new-service]          "
  })
  void mappingCausesServletContextToNotChangeServiceName(
      String context,
      @ConvertWith(ConfigDefaultsConverter.class) String serviceName,
      @ConvertWith(ConfigDefaultsConverter.class) Map<String, String> mapping) {
    CoreTracer tracer =
        tracerBuilder()
            .serviceName(serviceName)
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .serviceNameMappings(mapping)
            .build();

    AgentSpan span = tracer.buildSpan("datadog", "some span").start();
    span.setTag("servlet.context", context);
    span.finish();

    assertEquals("new-service", span.getServiceName());
  }

  private CoreTracer createSplittingTracer(String tag) {
    return tracerBuilder()
        .serviceName("my-service")
        .writer(new LoggingWriter())
        .sampler(new AllSampler())
        // equivalent to split-by-tags: tag
        .tagInterceptor(
            new TagInterceptor(
                true, "my-service", Collections.singleton(tag), new RuleFlags(), false))
        .build();
  }

  @TableTest({
    "scenario     | expected       | jeeActive",
    "jee inactive | 'some-context' | false    ",
    "jee active   | 'my-service'   | true     "
  })
  void splitByTagsForServletContextAndExperimentalJeeSplitByDeployment(
      String expected, boolean jeeActive) {
    CoreTracer tracer =
        tracerBuilder()
            .serviceName("my-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .tagInterceptor(
                new TagInterceptor(
                    false, "my-service", Collections.emptySet(), new RuleFlags(), jeeActive))
            .build();

    AgentSpan span = tracer.buildSpan("datadog", "some span").start();
    span.setTag(InstrumentationTags.SERVLET_CONTEXT, "some-context");
    span.finish();

    assertEquals(expected, span.getServiceName());
  }

  @Test
  void peerServiceThenSplitByTagsViaBuilder() {
    CoreTracer tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION);

    AgentSpan span =
        tracer
            .buildSpan("datadog", "some span")
            .withTag(Tags.PEER_SERVICE, "peer-service")
            .withTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
            .start();
    span.finish();

    assertEquals("some-queue", span.getServiceName());
  }

  @Test
  void peerServiceThenSplitByTagsViaSetTag() {
    CoreTracer tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION);

    AgentSpan span = tracer.buildSpan("datadog", "some span").start();
    span.setTag(Tags.PEER_SERVICE, "peer-service");
    span.setTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue");
    span.finish();

    assertEquals("some-queue", span.getServiceName());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void splitByTagsThenPeerServiceViaBuilder(boolean enabled) {
    injectSysConfig("dd.trace.PeerServiceTagInterceptor.enabled", String.valueOf(enabled), false);
    CoreTracer tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION);

    AgentSpan span =
        tracer
            .buildSpan("datadog", "some span")
            .withTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
            .withTag(Tags.PEER_SERVICE, "peer-service")
            .start();
    span.finish();

    assertEquals(enabled, "peer-service".equals(span.getServiceName()));
  }

  @Test
  @WithConfig(key = "dd.trace.PeerServiceTagInterceptor.enabled", value = "true", addPrefix = false)
  void splitByTagsThenPeerServiceViaSetTag() {
    CoreTracer tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION);

    AgentSpan span = tracer.buildSpan("datadog", "some span").start();
    span.setTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue");
    span.setTag(Tags.PEER_SERVICE, "peer-service");
    span.finish();

    assertEquals("peer-service", span.getServiceName());
  }

  @Test
  void setResourceName() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();

    String name = "my resource name";
    AgentSpan span = tracer.buildSpan("datadog", "test").start();
    span.setTag(DDTags.RESOURCE_NAME, name);
    span.finish();
    writer.waitForTraces(1);

    assertEquals(name, span.getResourceName().toString());
  }

  @Test
  void setResourceNameIgnoresNull() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();

    AgentSpan span = tracer.buildSpan("datadog", "test").withResourceName("keep").start();
    span.setTag(DDTags.RESOURCE_NAME, (String) null);
    span.finish();
    writer.waitForTraces(1);

    assertEquals("keep", span.getResourceName().toString());
  }

  @Test
  void setSpanType() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("datadog", "test").start();
    String type = DDSpanTypes.HTTP_CLIENT;
    span.setSpanType(type);
    span.finish();

    assertEquals(type, span.getSpanType());
  }

  @Test
  void setSpanTypeWithTag() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    AgentSpan span = tracer.buildSpan("datadog", "test").start();
    String type = DDSpanTypes.HTTP_CLIENT;
    span.setTag(DDTags.SPAN_TYPE, type);
    span.finish();
    writer.waitForTraces(1);

    assertEquals(type, span.getSpanType());
  }

  static Stream<Arguments> spanMetricsStartsEmptyButAddedWithRateLimitingValueArguments() {
    return Stream.of(
        arguments("int 0", 0, 0),
        arguments("int 1", 1, 1),
        arguments("float 0", 0f, 0f),
        arguments("float 1", 1f, 1f),
        arguments("double 0.1", 0.1, 0.1),
        arguments("double 1.1", 1.1, 1.1),
        arguments("int -1", -1, -1),
        arguments("int 10", 10, 10),
        arguments("string '00'", "00", 0.0),
        arguments("string '1'", "1", 1.0),
        arguments("string '1.0'", "1.0", 1.0),
        arguments("string '0'", "0", 0.0),
        arguments("string '0.1'", "0.1", 0.1),
        arguments("string '1.1'", "1.1", 1.1),
        arguments("string '-1'", "-1", -1.0),
        arguments("string 'str'", "str", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("spanMetricsStartsEmptyButAddedWithRateLimitingValueArguments")
  void spanMetricsStartsEmptyButAddedWithRateLimitingValue(
      String scenario, Object rate, Object result) throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    AgentSpan span = tracer.buildSpan("datadog", "test").start();

    assertNull(span.getTag(ANALYTICS_SAMPLE_RATE));

    span.setTag(ANALYTICS_SAMPLE_RATE, rate);
    span.finish();
    writer.waitForTraces(1);

    assertEquals(result, span.getTag(ANALYTICS_SAMPLE_RATE));
  }

  static Stream<Arguments> setPrioritySamplingViaTagArguments() {
    return Stream.of(
        arguments("manual.keep / true", DDTags.MANUAL_KEEP, true, (int) PrioritySampling.USER_KEEP),
        arguments("manual.keep / false", DDTags.MANUAL_KEEP, false, null),
        arguments(
            "manual.keep / 'true'", DDTags.MANUAL_KEEP, "true", (int) PrioritySampling.USER_KEEP),
        arguments("manual.keep / 'false'", DDTags.MANUAL_KEEP, "false", null),
        arguments("manual.keep / 'asdf'", DDTags.MANUAL_KEEP, "asdf", null),
        arguments("manual.drop / true", DDTags.MANUAL_DROP, true, (int) PrioritySampling.USER_DROP),
        arguments("manual.drop / false", DDTags.MANUAL_DROP, false, null),
        arguments(
            "manual.drop / 'true'", DDTags.MANUAL_DROP, "true", (int) PrioritySampling.USER_DROP),
        arguments("manual.drop / 'false'", DDTags.MANUAL_DROP, "false", null),
        arguments("manual.drop / 'asdf'", DDTags.MANUAL_DROP, "asdf", null),
        arguments("asm.keep / true", Tags.ASM_KEEP, true, (int) PrioritySampling.USER_KEEP),
        arguments("asm.keep / false", Tags.ASM_KEEP, false, null),
        arguments("asm.keep / 'true'", Tags.ASM_KEEP, "true", (int) PrioritySampling.USER_KEEP),
        arguments("asm.keep / 'false'", Tags.ASM_KEEP, "false", null),
        arguments("asm.keep / 'asdf'", Tags.ASM_KEEP, "asdf", null),
        arguments(
            "sampling.priority / -1", Tags.SAMPLING_PRIORITY, -1, (int) PrioritySampling.USER_DROP),
        arguments(
            "sampling.priority / 0", Tags.SAMPLING_PRIORITY, 0, (int) PrioritySampling.USER_DROP),
        arguments(
            "sampling.priority / 1", Tags.SAMPLING_PRIORITY, 1, (int) PrioritySampling.USER_KEEP),
        arguments(
            "sampling.priority / 2", Tags.SAMPLING_PRIORITY, 2, (int) PrioritySampling.USER_KEEP),
        arguments(
            "sampling.priority / '-1'",
            Tags.SAMPLING_PRIORITY,
            "-1",
            (int) PrioritySampling.USER_DROP),
        arguments(
            "sampling.priority / '0'",
            Tags.SAMPLING_PRIORITY,
            "0",
            (int) PrioritySampling.USER_DROP),
        arguments(
            "sampling.priority / '1'",
            Tags.SAMPLING_PRIORITY,
            "1",
            (int) PrioritySampling.USER_KEEP),
        arguments(
            "sampling.priority / '2'",
            Tags.SAMPLING_PRIORITY,
            "2",
            (int) PrioritySampling.USER_KEEP),
        arguments("sampling.priority / 'asdf'", Tags.SAMPLING_PRIORITY, "asdf", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("setPrioritySamplingViaTagArguments")
  void setPrioritySamplingViaTag(String scenario, String tag, Object value, Integer expected) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("datadog", "test").start();
    span.setTag(tag, value);

    assertEquals(expected, span.getSamplingPriority());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void setErrorFlagWhenErrorTagReported(boolean error) throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    AgentSpan span = tracer.buildSpan("datadog", "test").start();
    span.setTag(Tags.ERROR, error);
    span.finish();
    writer.waitForTraces(1);

    assertEquals(error, span.isError());
  }

  static Stream<Arguments> interceptorsApplyToBuilderTooArguments() {
    return Stream.of(
        arguments(
            "serviceName",
            DDTags.SERVICE_NAME,
            "my-service",
            (Function<DDSpanContext, Object>) DDSpanContext::getServiceName),
        arguments(
            "resourceName",
            DDTags.RESOURCE_NAME,
            "my-resource",
            (Function<DDSpanContext, Object>) ctx -> ctx.getResourceName().toString()),
        arguments(
            "spanType",
            DDTags.SPAN_TYPE,
            "my-span-type",
            (Function<DDSpanContext, Object>) DDSpanContext::getSpanType));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("interceptorsApplyToBuilderTooArguments")
  void interceptorsApplyToBuilderToo(
      String attribute, String name, String value, Function<DDSpanContext, Object> getter)
      throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();

    AgentSpan span = tracer.buildSpan("datadog", "interceptor.test").withTag(name, value).start();
    span.finish();
    writer.waitForTraces(1);

    assertEquals(value, getter.apply((DDSpanContext) span.context()));
  }

  @Test
  void decoratorsApplyToBuilderToo() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();

    AgentSpan span =
        tracer.buildSpan("datadog", "decorator.test").withTag("sn.tag1", "some val").start();
    span.finish();
    writer.waitForTraces(1);
    assertEquals("some val", span.getServiceName());

    span =
        tracer
            .buildSpan("datadog", "decorator.test")
            .withTag("servlet.context", "/my-servlet")
            .start();
    assertEquals("my-servlet", span.getServiceName());

    span = tracer.buildSpan("datadog", "decorator.test").withTag("error", "true").start();
    span.finish();
    writer.waitForTraces(2);
    assertTrue(span.isError());

    span =
        tracer
            .buildSpan("datadog", "decorator.test")
            .withTag(Tags.DB_STATEMENT, "some-statement")
            .start();
    span.finish();
    writer.waitForTraces(3);
    assertEquals("some-statement", span.getResourceName().toString());
  }

  @TableTest({
    "scenario           | decorator                   | enabled",
    "lowercase enabled  | 'servicenametaginterceptor' | true   ",
    "camelCase enabled  | 'ServiceNameTagInterceptor' | true   ",
    "lowercase disabled | 'servicenametaginterceptor' | false  ",
    "camelCase disabled | 'ServiceNameTagInterceptor' | false  "
  })
  void disableDecoratorViaConfig(String decorator, boolean enabled) {
    injectSysConfig("dd.trace." + decorator + ".enabled", String.valueOf(enabled), false);

    CoreTracer tracer =
        tracerBuilder()
            .serviceName("some-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .build();

    AgentSpan span =
        tracer
            .buildSpan("datadog", "some span")
            .withTag(DDTags.SERVICE_NAME, "other-service")
            .start();
    span.finish();

    assertEquals(enabled ? "other-service" : "some-service", span.getServiceName());
  }

  @TableTest({
    "scenario     | tag                   | name          | expected      ",
    "service.name | 'DDTags.SERVICE_NAME' | 'new-service' | 'some-service'",
    "service      | 'service'             | 'new-service' | 'some-service'",
    "sn.tag1      | 'sn.tag1'             | 'new-service' | 'new-service' "
  })
  void disablingServiceDecoratorDoesNotDisableSplitByTags(
      @ConvertWith(DDTagsConverter.class) String tag, String name, String expected) {
    injectSysConfig("dd.trace.ServiceNameTagInterceptor.enabled", "false", false);

    CoreTracer tracer =
        tracerBuilder()
            .serviceName("some-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .build();

    AgentSpan span = tracer.buildSpan("datadog", "some span").withTag(tag, name).start();
    span.finish();

    assertEquals(expected, span.getServiceName());
  }

  @Test
  void changeTopLevelStatusWhenChangingServiceName() {
    CoreTracer tracer =
        tracerBuilder()
            .serviceName("some-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .build();

    AgentSpan parent = tracer.buildSpan("datadog", "parent").withServiceName("parent").start();

    // the service name doesn't match the parent
    AgentSpan child =
        tracer.buildSpan("datadog", "child").withServiceName("child").asChildOf(parent).start();
    assertTrue(((CoreSpan<?>) child).isTopLevel());

    // the service name is changed to match the parent
    child.setTag(DDTags.SERVICE_NAME, "parent");
    assertFalse(((CoreSpan<?>) child).isTopLevel());

    // the service name is changed to no longer match the parent
    child.setTag(DDTags.SERVICE_NAME, "foo");
    assertTrue(((CoreSpan<?>) child).isTopLevel());
  }

  static Stream<Arguments> treat1ValueAsTrueForBooleanTagValuesArguments() {
    return Stream.of(
        arguments("manual.drop / true", DDTags.MANUAL_DROP, true, (int) PrioritySampling.USER_DROP),
        arguments("manual.drop / '1'", DDTags.MANUAL_DROP, "1", (int) PrioritySampling.USER_DROP),
        arguments("manual.drop / false", DDTags.MANUAL_DROP, false, null),
        arguments("manual.drop / '0'", DDTags.MANUAL_DROP, "0", null),
        arguments("manual.keep / true", DDTags.MANUAL_KEEP, true, (int) PrioritySampling.USER_KEEP),
        arguments("manual.keep / '1'", DDTags.MANUAL_KEEP, "1", (int) PrioritySampling.USER_KEEP),
        arguments("manual.keep / false", DDTags.MANUAL_KEEP, false, null),
        arguments("manual.keep / '0'", DDTags.MANUAL_KEEP, "0", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("treat1ValueAsTrueForBooleanTagValuesArguments")
  void treat1ValueAsTrueForBooleanTagValues(
      String scenario, String tag, Object value, Integer samplingPriority) {
    CoreTracer tracer =
        tracerBuilder()
            .serviceName("some-service")
            .writer(new LoggingWriter())
            .sampler(new AllSampler())
            .build();

    AgentSpan span = tracer.buildSpan("datadog", "test").start();
    assertNull(span.getSamplingPriority());

    span.setTag(tag, value);
    assertEquals(samplingPriority, span.getSamplingPriority());
  }

  @TableTest({
    "scenario     | value                       | resourceName        | meta                    ",
    "null url     |                             | 'fakeOperation'     | [:]                     ",
    "space url    | ' '                         | '/'                 | [:]                     ",
    "tab url      | '\t'                        | '/'                 | [:]                     ",
    "simple path  | '/path'                     | '/path'             | [:]                     ",
    "complex path | '/ABC/a-1/b_2/c.3/d4d/5f/6' | '/ABC/?/?/?/?/?/?'  | [:]                     ",
    "not found    | '/not-found'                | '404'               | [Tags.HTTP_STATUS: 404] ",
    "with method  | '/with-method'              | 'POST /with-method' | [Tags.HTTP_METHOD: Post]"
  })
  void urlAsResourceNameRuleSetsTheResourceName(
      String value,
      String resourceName,
      @ConvertWith(DDTagsConverter.class) Map<String, String> meta) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan span = tracer.buildSpan("datadog", "fakeOperation").start();
    for (Map.Entry<String, String> entry : meta.entrySet()) {
      span.setTag(entry.getKey(), entry.getValue());
    }

    span.setTag(Tags.HTTP_URL, value);

    try {
      assertEquals(resourceName, span.getResourceName().toString());
    } finally {
      span.finish();
    }
  }

  @Test
  void whenUserSetsPeerServiceTheSourceShouldBePeerService() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    AgentSpan span = tracer.buildSpan("datadog", "fakeOperation").start();
    try {
      span.setTag(Tags.PEER_SERVICE, "test");
      assertEquals("peer.service", span.getTag(DDTags.PEER_SERVICE_SOURCE));
    } finally {
      span.finish();
    }
  }

  @Test
  void whenInterceptServiceNameExtraServiceProviderIsCalled() {
    ServiceNameCollector origServiceNameCollector = ServiceNameCollector.get();
    ServiceNameCollector extraServiceProvider = mock(ServiceNameCollector.class);
    ServiceNameCollectorTestBridge.setInstance(extraServiceProvider);
    try {
      RuleFlags ruleFlags = mock(RuleFlags.class);
      when(ruleFlags.isEnabled(any())).thenReturn(true);
      TagInterceptor interceptor =
          new TagInterceptor(
              true, "my-service", Collections.singleton(DDTags.SERVICE_NAME), ruleFlags, false);

      interceptor.interceptServiceName(null, mock(DDSpanContext.class), "some-service");

      verify(extraServiceProvider, times(1)).addService("some-service");
    } finally {
      ServiceNameCollectorTestBridge.setInstance(origServiceNameCollector);
    }
  }

  @TableTest({
    "scenario     | value   | expected      ",
    "root context | '/'     | 'root-servlet'",
    "/test path   | '/test' | 'test'        ",
    "test path    | 'test'  | 'test'        "
  })
  void whenInterceptServletContextExtraServiceProviderIsCalled(String value, String expected) {
    ServiceNameCollector origServiceNameCollector = ServiceNameCollector.get();
    ServiceNameCollector extraServiceProvider = mock(ServiceNameCollector.class);
    ServiceNameCollectorTestBridge.setInstance(extraServiceProvider);
    try {
      RuleFlags ruleFlags = mock(RuleFlags.class);
      when(ruleFlags.isEnabled(any())).thenReturn(true);
      TagInterceptor interceptor =
          new TagInterceptor(
              true, "my-service", Collections.singleton("servlet.context"), ruleFlags, false);

      interceptor.interceptServletContext(mock(DDSpanContext.class), value);

      verify(extraServiceProvider, times(1)).addService(expected);
    } finally {
      ServiceNameCollectorTestBridge.setInstance(origServiceNameCollector);
    }
  }

  @Test
  void whenInterceptsProductTraceSourcePropagationTagUpdatePropagatedTraceSourceIsCalled() {
    RuleFlags ruleFlags = mock(RuleFlags.class);
    when(ruleFlags.isEnabled(any())).thenReturn(true);
    TagInterceptor interceptor = new TagInterceptor(ruleFlags);
    DDSpanContext context = mock(DDSpanContext.class);

    interceptor.interceptTag(context, Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);

    verify(context, times(1)).addPropagatedTraceSource(ProductTraceSource.ASM);
  }
}
