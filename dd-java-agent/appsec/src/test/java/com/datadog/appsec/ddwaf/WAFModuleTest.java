package com.datadog.appsec.ddwaf;

import static com.datadog.appsec.TestResources.readResource;
import static com.datadog.appsec.event.data.KnownAddresses.HEADERS_NO_COOKIES;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_BODY_OBJECT;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_COOKIES;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_INFERRED_CLIENT_IP;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_METHOD;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_PATH_PARAMS;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_QUERY;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_URI_RAW;
import static com.datadog.appsec.event.data.KnownAddresses.SESSION_ID;
import static com.datadog.appsec.event.data.KnownAddresses.USER_ID;
import static com.datadog.appsec.event.data.KnownAddresses.WAF_CONTEXT_PROCESSOR;
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP;
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP;
import static datadog.trace.api.config.AppSecConfig.APPSEC_TRACE_RATE_LIMIT;
import static datadog.trace.api.config.AppSecConfig.APPSEC_WAF_METRICS;
import static datadog.trace.api.config.AppSecConfig.APPSEC_WAF_TIMEOUT;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.removeSysConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datadog.appsec.AppSecModule.AppSecModuleActivationException;
import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.config.AppSecConfigService;
import com.datadog.appsec.config.AppSecConfigServiceImpl;
import com.datadog.appsec.config.AppSecModuleConfigurer;
import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.DataListener;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.AddressTestBridge;
import com.datadog.appsec.event.data.CaseInsensitiveMap;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.MapDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.appsec.gateway.RateLimiterTestBridge;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.ddwaf.Waf;
import com.datadog.ddwaf.WafContext;
import com.datadog.ddwaf.WafErrorCode;
import com.datadog.ddwaf.WafHandle;
import com.datadog.ddwaf.WafMetrics;
import com.datadog.ddwaf.exception.AbstractWafException;
import com.datadog.ddwaf.exception.InternalWafException;
import com.datadog.ddwaf.exception.InvalidArgumentWafException;
import com.datadog.ddwaf.exception.InvalidObjectWafException;
import com.datadog.ddwaf.exception.UnclassifiedWafException;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.metrics.api.Monitoring;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.telemetry.RuleType;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import datadog.trace.util.stacktrace.StackTraceEvent;
import datadog.trace.util.stacktrace.StackTraceFrame;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.tabletest.junit.TableTest;

class WAFModuleTest extends DDJavaSpecification {

  private static final TracerAPI ORIGINAL_TRACER = AgentTracer.get();
  private static final WafMetricCollector ORIGINAL_METRIC_COLLECTOR = WafMetricCollector.get();
  private static final JsonAdapter<Map<String, Object>> ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));
  private static final DataBundle ATTACK_BUNDLE = userAgentBundle("Arachni/v0");
  private static final String UUID_PATTERN = "[0-9a-f-]{36}";

  private final TracerAPI tracer = mock(TracerAPI.class);
  private final AgentSpan activeSpan = mock(AgentSpan.class);
  private final AgentSpan localRootSpan = mock(AgentSpan.class);
  private final AppSecRequestContext ctx = spy(new AppSecRequestContext());
  private GatewayContext gwCtx = new GatewayContext(false);
  private AppSecConfigServiceImpl service;
  private final AppSecModuleConfigurer.Reconfiguration serviceReconfiguration = () -> {};
  private WAFModule wafModule = new WAFModule();
  private DataListener dataListener;
  private WafContext wafContext;
  private final WafMetrics metrics = new WafMetrics();
  private final WafMetricCollector wafMetricCollector = mock(WafMetricCollector.class);
  private AppSecConfigService.TransactionalAppSecModuleConfigurer cfg;
  private ProductListener listener;
  private final AppSecModuleConfigurer.Reconfiguration reconf =
      mock(AppSecModuleConfigurer.Reconfiguration.class);
  private boolean originalActive;

  @BeforeEach
  void setup() {
    when(tracer.activeSpan()).thenReturn(activeSpan);
    when(activeSpan.getSpanId()).thenReturn(777L);
    when(activeSpan.getLocalRootSpan()).thenReturn(localRootSpan);

    WafMetricCollector.INSTANCE = wafMetricCollector;
    AgentTracer.forceRegister(tracer);
    originalActive = AppSecSystem.isActive();
    AppSecSystem.setActive(true);

    ConfigurationPoller configurationPoller = mock(ConfigurationPoller.class);
    service =
        new AppSecConfigServiceImpl(Config.get(), configurationPoller, serviceReconfiguration);
    service.init();
    service.maybeSubscribeConfigPolling();
    ArgumentCaptor<ProductListener> listenerCaptor = ArgumentCaptor.forClass(ProductListener.class);
    verify(configurationPoller).addListener(eq(Product.ASM_DD), listenerCaptor.capture());
    listener = listenerCaptor.getValue();
    assertNotNull(listener);

    cfg = service.createAppSecModuleConfigurer();
    cfg.commit();

    // only count the interactions of the test itself, not the ones of the setup
    clearInvocations(tracer, wafMetricCollector);
  }

  @AfterEach
  void cleanup() {
    WafMetricCollector.INSTANCE = ORIGINAL_METRIC_COLLECTOR;
    AgentTracer.forceRegister(ORIGINAL_TRACER);
    service.close();
    if (wafContext != null) {
      wafContext.close();
    }
    AppSecSystem.setActive(originalActive);
  }

  @Test
  void overrideOnMatchThroughReconfiguration() throws Exception {
    ChangeableFlow flow = mock(ChangeableFlow.class);
    doReturn(false).when(ctx).isWafContextClosed();

    // when
    initialRuleAdd("override_actions_config.json");

    String actions = readResource("waf_module/override_on_match_actions.json");

    String ipData = readResource("waf_module/override_on_match_ip_data.json");

    send("b", actions);
    send("c", ipData);

    wafModule.applyConfig(reconf);
    DataBundle newBundle = MapDataBundle.of(REQUEST_INFERRED_CLIENT_IP, "1.2.3.4");
    dataListener.onDataAvailable(flow, ctx, newBundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafInit();
    verifyConfigUpdated();
    verify(flow).setAction(argThat(blockingAction(501, BlockingContentType.JSON)));
    verifyWafRunAndContextClose();
    verify(tracer, times(2)).activeSpan();
    verifyEventReported();
    verify(flow).isBlocking();
    verifyNoMoreMockInteractions(flow);
  }

  @Test
  void provideDataThroughTheInitialConfig() throws Exception {
    ChangeableFlow flow = mock(ChangeableFlow.class);
    AppSecModuleConfigurer.Reconfiguration newReconf =
        mock(AppSecModuleConfigurer.Reconfiguration.class);
    doReturn(false).when(ctx).isWafContextClosed();
    stubWafContextCreation();

    // when
    initialRuleAdd("rules_with_data_config.json");
    DataBundle bundle = MapDataBundle.of(USER_ID, "user-to-block-1");
    wafModule.applyConfig(serviceReconfiguration);
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafInit();
    verify(wafMetricCollector).wafUpdates(any(), eq(true));
    verify(flow).setAction(argThat(blockingAction(403, BlockingContentType.AUTO)));
    verifyWafRunAndContextClose();
    verify(tracer, times(2)).activeSpan();
    verifyEventReported();
    verify(flow).isBlocking();
    verifyNoMoreMockInteractions(flow, newReconf);
    nextBlock(flow, newReconf);

    // when: merges new waf data with the one in the rules config
    String newData = readResource("waf_module/blocked_users_data.json");
    send("c", newData);
    wafModule.applyConfig(newReconf);
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verify(wafMetricCollector).wafUpdates(any(), eq(true));
    verify(newReconf).reloadSubscriptions();
    verify(flow).setAction(argThat(blockingAction(403, BlockingContentType.AUTO)));
    verifyWafRunAndContextClose();
    verify(tracer, times(2)).activeSpan();
    verifyEventReported();
    verify(flow).isBlocking();
    verifyNoMoreMockInteractions(flow, newReconf);
    nextBlock(flow, newReconf);

    // when
    bundle = MapDataBundle.of(USER_ID, "user-to-block-2");
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verify(flow).setAction(argThat(blockingAction(403, BlockingContentType.AUTO)));
    verifyWafRunAndContextClose();
    verify(tracer, times(2)).activeSpan();
    verifyEventReported();
    verify(flow).isBlocking();
    verifyNoMoreMockInteractions(flow, newReconf);
    nextBlock(flow, newReconf);

    // when: changes the rules config
    String newCfg = readResource("waf_module/block_users_rules_config.json");
    send("initial_waf", null);
    send("waf", newCfg);
    wafModule.applyConfig(newReconf);

    // and
    bundle = MapDataBundle.of(USER_ID, "user-to-block-2");
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verify(wafMetricCollector).wafUpdates(any(), eq(true));
    verify(newReconf).reloadSubscriptions();
    verify(flow).setAction(argThat(blockingAction(403, BlockingContentType.AUTO)));
    verifyWafRunAndContextClose();
    verify(tracer, times(2)).activeSpan();
    verifyEventReported();
    verify(flow).isBlocking();
    verifyNoMoreMockInteractions(flow, newReconf);
    nextBlock(flow, newReconf);

    // when
    bundle = MapDataBundle.of(USER_ID, "user-to-block-1");
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verifyNoMoreMockInteractions(flow, newReconf);
  }

  @Test
  void addExclusionsThroughReconfiguration() throws Exception {
    ChangeableFlow flow = new ChangeableFlow();
    doReturn(false).when(ctx).isWafContextClosed();
    stubWafContextCreation();

    // when
    initialRuleAdd();
    String exclusions = readResource("waf_module/exclusions_config.json");

    send("b", exclusions);
    wafModule.applyConfig(reconf);

    // then
    verifyWafInit();
    verifyConfigUpdated();
    verifyNoMoreMockInteractions();
    nextBlock();

    // when
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verify(tracer, times(2)).activeSpan();
    verifyEventReported();
    verify(ctx).setWafBlocked();
    verifyNoMoreMockInteractions();
    nextBlock();

    // when
    DataBundle newBundle =
        MapDataBundle.of(
            HEADERS_NO_COOKIES,
            headers("user-agent", "Arachni/v0"),
            REQUEST_INFERRED_CLIENT_IP,
            "192.168.0.1");
    dataListener.onDataAvailable(flow, ctx, newBundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verifyNoMoreMockInteractions();
  }

  @Test
  void addCustomRuleThroughReconfiguration() throws Exception {
    ChangeableFlow flow = new ChangeableFlow();
    doReturn(false).when(ctx).isWafContextClosed();

    // when
    initialRuleAdd();

    String customRules = readResource("waf_module/custom_rules_config.json");

    send("b", customRules);
    wafModule.applyConfig(reconf);

    // then
    verifyWafInit();
    verifyConfigUpdated();
    verifyNoMoreMockInteractions();
    nextBlock();

    // when
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    verify(ctx).getWafContextIfReady(true, false);
    verify(ctx).getOrCreateWafContext(any(), eq(true), eq(false));
    verify(tracer, times(2)).activeSpan();
    verify(ctx).reportEvents(argThat(events -> events.size() == 1));
    verify(ctx, times(2)).getWafMetrics();
    verify(ctx).isWafContextClosed();
    verify(ctx).closeWafContext();
    verify(ctx).setWafBlocked();
    verify(ctx).isThrottled(null);
    verifyTraceKept();
    verifyNoMoreMockInteractions();
  }

  @Test
  void replaceActionsThroughRuntimeConfiguration() throws Exception {
    ChangeableFlow flow = mock(ChangeableFlow.class);
    doReturn(false).when(ctx).isWafContextClosed();

    // when
    initialRuleAdd("test_multi_config_no_action.json");

    // original action
    String action1 = readResource("waf_module/block_418_html_action.json");

    String action2 = readResource("waf_module/block_401_action.json");

    send("original config", action1);
    send("original config", null);
    send("new config", action2);
    wafModule.applyConfig(reconf);

    // then
    verifyWafInit();
    verifyConfigUpdated();
    verifyNoMoreMockInteractions(flow);
    nextBlock(flow);

    // when
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    // original rule is replaced; no attack
    verify(flow).setAction(argThat(blockingAction(401, BlockingContentType.AUTO)));
    verifyWafRunAndContextClose();
    verify(tracer, times(2)).activeSpan();
    verifyEventReported();
    verify(flow).isBlocking();
    verifyNoMoreMockInteractions(flow);
  }

  @TableTest({
    "scenario  | variant | statusCode",
    "redirect1 | 1       | 303       ",
    "redirect2 | 2       | 301       ",
    "redirect3 | 3       | 303       "
  })
  void redirectActionsAreCorrectlyProcessed(int variant, int statusCode) throws Exception {
    doReturn(metrics).when(ctx).getWafMetrics();
    doReturn(false).when(ctx).isWafContextClosed();

    // when
    initialRuleAdd("redirect_actions.json");
    wafModule.applyConfig(reconf);
    DataBundle bundle = userAgentBundle("redirect" + variant);
    ChangeableFlow flow = new ChangeableFlow();
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verifyEventReported();
    verify(ctx).setWafBlocked();
    verifyNoMoreInteractions(ctx);
    assertTrue(flow.isBlocking());
    Flow.Action.RequestBlockingAction action =
        assertInstanceOf(Flow.Action.RequestBlockingAction.class, flow.getAction());
    assertEquals(statusCode, action.getStatusCode());
    // Location header may include security_response_id parameter from libddwaf
    String location = action.getExtraHeaders().get("Location");
    assertTrue(location.startsWith("https://example" + variant + ".com/"));
    // If security_response_id is present, it should be a valid UUID
    if (location.contains("security_response_id=")) {
      assertTrue(location.matches(".*security_response_id=" + UUID_PATTERN + ".*"));
    }
  }

  @Test
  void isNamedDdwaf() {
    // expect
    assertEquals("ddwaf", wafModule.getName());
  }

  @Test
  void reportWafStatsOnFirstSpan() throws Exception {
    // setup
    TraceSegment segment = mock(TraceSegment.class);
    initialRuleAdd();

    wafModule.applyConfig(reconf);

    // when
    TraceSegmentPostProcessor pp = service.getTraceSegmentPostProcessors().get(0);
    pp.processTraceSegment(segment, ctx, emptyList());

    // then
    verify(segment).setTagTop(eq("_dd.appsec.waf.version"), any(String.class));
    verify(segment).setTagTop("_dd.appsec.event_rules.loaded", 117);
    verify(segment).setTagTop("_dd.appsec.event_rules.error_count", 1);
    Pattern ruleErrors = Pattern.compile("\\{\"[^\"]+\":\\[\"bad rule\"]}");
    verify(segment)
        .setTagTop(
            eq("_dd.appsec.event_rules.errors"),
            argThat(value -> ruleErrors.matcher(String.valueOf(value)).find()));
    verify(segment).setTagTop("asm.keep", true);
    verifyNoMoreInteractions(segment);
    clearInvocations(segment);

    // when
    pp.processTraceSegment(segment, ctx, emptyList());

    // then
    verifyNoInteractions(segment);
  }

  @Test
  void triggersARuleThroughTheUserAgentHeader() throws Exception {
    initialRuleAdd();
    ChangeableFlow flow = new ChangeableFlow();
    wafModule.applyConfig(reconf);
    doReturn(metrics).when(ctx).getWafMetrics();
    doReturn(false).when(ctx).isWafContextClosed();

    // when
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verifyEventReported();
    verify(ctx).setWafBlocked();
    verifyNoMoreInteractions(ctx);
    assertTrue(flow.isBlocking());
    Flow.Action.RequestBlockingAction action = (Flow.Action.RequestBlockingAction) flow.getAction();
    assertEquals(418, action.getStatusCode());
    assertEquals(BlockingContentType.HTML, action.getBlockingContentType());
  }

  @Test
  void noMetricsAreSetIfWafMetricsAreOff() throws Exception {
    // setup
    // injected after the service setup, so that only the WAF module is affected
    injectSysConfig(APPSEC_WAF_METRICS, "false");
    wafModule = new WAFModule(); // replace the one created too soon
    initialRuleAdd();
    ChangeableFlow flow = new ChangeableFlow();
    wafModule.applyConfig(reconf);
    doReturn(null).when(ctx).getWafMetrics();
    doReturn(false).when(ctx).isWafContextClosed();

    // when
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    verify(ctx).getWafContextIfReady(false, false);
    verify(ctx).getOrCreateWafContext(any(), eq(false), eq(false));
    verify(ctx, times(2)).getWafMetrics();
    verify(ctx).isWafContextClosed();
    verify(ctx).closeWafContext();
    verifyEventReported();
    verify(ctx).setWafBlocked();
    verifyNoMoreInteractions(ctx);
  }

  @Test
  void reportsWafMetrics() throws Exception {
    // setup
    TraceSegment segment = mock(TraceSegment.class);
    Flow<Void> flow = new ChangeableFlow();
    initialRuleAdd();
    doReturn(new FixedWafMetrics(1000, 2000)).when(ctx).getWafMetrics();

    // when
    TraceSegmentPostProcessor pp = service.getTraceSegmentPostProcessors().get(1);
    dataListener.onDataAvailable((ChangeableFlow) flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();
    pp.processTraceSegment(segment, ctx, emptyList());

    // then
    verify(ctx).getWafContextIfReady(true, false);
    verify(ctx).getOrCreateWafContext(any(), eq(true), eq(false));
    verify(ctx).closeWafContext();
    verify(ctx, times(3)).getWafMetrics();

    verify(segment).setTagTop("_dd.appsec.waf.duration", 1L);
    verify(segment).setTagTop("_dd.appsec.waf.duration_ext", 2L);
    verify(segment).setTagTop("_dd.appsec.event_rules.version", "0.42.0");

    verifyNoMoreInteractions(segment);
  }

  @Test
  void canTriggerANonWafContextWafRun() throws Exception {
    initialRuleAdd();
    ChangeableFlow flow = new ChangeableFlow();
    wafModule.applyConfig(reconf);
    doReturn(metrics).when(ctx).getWafMetrics();
    doReturn(false).when(ctx).isWafContextClosed();

    // when
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);

    // then
    verify(ctx).getWafContextIfReady(true, false);
    verify(ctx).getOrCreateWafContext(any(), eq(true), eq(false));
    verify(ctx, times(2)).getWafMetrics();
    verifyEventReported();
    verify(ctx).setWafBlocked();
    verify(ctx).isWafContextClosed();
    verifyNoMoreInteractions(ctx);
    assertTrue(flow.isBlocking());
  }

  @Test
  void reportsEvents() throws Exception {
    // setup
    initialRuleAdd();
    wafModule.applyConfig(reconf);
    DataBundle attackBundle = userAgentBundle("Arachni/generate-stacktrace");

    // when
    dataListener.onDataAvailable(mock(ChangeableFlow.class), ctx, attackBundle, gwCtx);
    ctx.closeWafContext();

    // then
    verify(ctx).getWafContextIfReady(eq(true), anyBoolean());
    verify(ctx).getOrCreateWafContext(any(), eq(true), anyBoolean());
    AppSecEvent event = verifyReportedEvent();
    ArgumentCaptor<StackTraceEvent> stackTraceCaptor =
        ArgumentCaptor.forClass(StackTraceEvent.class);
    verify(ctx).reportStackTrace(stackTraceCaptor.capture());
    StackTraceEvent stackTrace = stackTraceCaptor.getValue();

    assertEquals("generate-stacktrace-on-scanner", event.getRule().id);
    assertEquals("Arachni", event.getRule().name);
    Map<String, String> expectedTags = new HashMap<>();
    expectedTags.put("type", "security_scanner");
    expectedTags.put("category", "attack_attempt");
    assertEquals(expectedTags, event.getRule().tags);

    WAFResultData.RuleMatch ruleMatch = event.getRuleMatches().get(0);
    assertEquals("match_regex", ruleMatch.operator);
    assertEquals("^Arachni\\/generate-stacktrace", ruleMatch.operator_value);
    WAFResultData.Parameter parameter = ruleMatch.parameters.get(0);
    assertEquals("server.request.headers.no_cookies", parameter.address);
    assertEquals(singletonList("Arachni/generate-stacktrace"), parameter.highlight);
    assertEquals(singletonList("user-agent"), parameter.key_path);
    assertEquals("Arachni/generate-stacktrace", parameter.value);

    assertEquals(777L, event.getSpanId());

    assertEquals("java", stackTrace.getLanguage());
    assertEquals("Exploit detected", stackTrace.getMessage());
    assertTrue(stackTrace.getFrames().size() >= 1);
    // datadog frames (WAF module and this test) are filtered out
    for (StackTraceFrame frame : stackTrace.getFrames()) {
      assertFalse(frame.getClass_name().startsWith("com.datadog.appsec."), frame.getClass_name());
      assertFalse(frame.getClass_name().startsWith("datadog.trace."), frame.getClass_name());
    }
    // the first frame after the JDK reflection ones is the JUnit method invocation
    StackTraceFrame firstFrame = firstNonReflectionFrame(stackTrace.getFrames());
    assertEquals("org.junit.platform.commons.util.ReflectionUtils", firstFrame.getClass_name());
    assertEquals("invokeMethod", firstFrame.getFunction());
  }

  @Test
  void redactionWithDefaultSettings() throws Exception {
    initialRuleAdd();

    // when
    DataBundle bundle =
        MapDataBundle.of(
            HEADERS_NO_COOKIES, headers("user-agent", singletonMap("password", "Arachni/v0")));
    dataListener.onDataAvailable(mock(ChangeableFlow.class), ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verify(ctx).getWafContextIfReady(eq(true), anyBoolean());
    verify(ctx).getOrCreateWafContext(any(), eq(true), anyBoolean());
    AppSecEvent event = verifyReportedEvent();

    WAFResultData.Parameter parameter = event.getRuleMatches().get(0).parameters.get(0);
    assertEquals("server.request.headers.no_cookies", parameter.address);
    assertEquals(singletonList("<Redacted>"), parameter.highlight);
    assertEquals(asList("user-agent", "password"), parameter.key_path);
    assertEquals("<Redacted>", parameter.value);
  }

  @Test
  @WithConfig(key = APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP, value = "")
  void disablingOfKeyRegex() throws Exception {
    initialRuleAdd();

    // when
    DataBundle bundle =
        MapDataBundle.of(
            HEADERS_NO_COOKIES, headers("user-agent", singletonMap("password", "Arachni/v0")));
    dataListener.onDataAvailable(mock(ChangeableFlow.class), ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    AppSecEvent event = verifyReportedEvent();

    WAFResultData.Parameter parameter = event.getRuleMatches().get(0).parameters.get(0);
    assertEquals("server.request.headers.no_cookies", parameter.address);
    assertEquals(singletonList("Arachni/v"), parameter.highlight);
    assertEquals(asList("user-agent", "password"), parameter.key_path);
    assertEquals("Arachni/v0", parameter.value);
  }

  @Test
  @WithConfig(key = APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP, value = "Arachni")
  void redactionOfValues() throws Exception {
    initialRuleAdd();

    // when
    dataListener.onDataAvailable(mock(ChangeableFlow.class), ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    verify(ctx).getWafContextIfReady(eq(true), anyBoolean());
    verify(ctx).getOrCreateWafContext(any(), eq(true), anyBoolean());
    AppSecEvent event = verifyReportedEvent();

    WAFResultData.Parameter parameter = event.getRuleMatches().get(0).parameters.get(0);
    assertEquals("server.request.headers.no_cookies", parameter.address);
    assertEquals(singletonList("<Redacted>"), parameter.highlight);
    assertEquals(singletonList("user-agent"), parameter.key_path);
    assertEquals("<Redacted>", parameter.value);
  }

  @Test
  void triggersNoRule() throws Exception {
    initialRuleAdd();
    ChangeableFlow flow = new ChangeableFlow();
    DataBundle db = userAgentBundle("Harmless");

    // when
    dataListener.onDataAvailable(flow, ctx, db, gwCtx);

    // then
    verify(ctx).getWafContextIfReady(eq(true), anyBoolean());
    verify(ctx).getOrCreateWafContext(any(), eq(true), anyBoolean());
    assertFalse(flow.isBlocking());
  }

  @Test
  void nonStringTypesWork() throws Exception {
    initialRuleAdd();
    ChangeableFlow flow = new ChangeableFlow();
    List<Object> values =
        Arrays.<Object>asList(
            true,
            (byte) 1,
            (short) 2,
            3,
            4L,
            5.0f,
            6.0d,
            '7',
            new BigDecimal("8.0"),
            new BigInteger("9"));
    DataBundle db =
        MapDataBundle.of(REQUEST_BODY_OBJECT, singletonList(singletonMap("key", values)));

    // when
    dataListener.onDataAvailable(flow, ctx, db, gwCtx);

    // then
    verify(ctx).getWafContextIfReady(eq(true), anyBoolean());
    verify(ctx).getOrCreateWafContext(any(), eq(true), anyBoolean());
    assertFalse(flow.isBlocking());
  }

  @Test
  void wafExceptionsDoNotPropagate() throws Exception {
    initialRuleAdd();
    ChangeableFlow flow = new ChangeableFlow();
    DataBundle db =
        MapDataBundle.ofDelegate(singletonMap(HEADERS_NO_COOKIES, new OnlyGetNullList()));

    // when
    dataListener.onDataAvailable(flow, ctx, db, gwCtx);

    // then
    verify(ctx).getWafContextIfReady(true, false);
    verify(ctx).getOrCreateWafContext(any(), eq(true), eq(false));
    verify(ctx, times(2)).getWafMetrics();
    verify(ctx).setWafErrors();
    verify(wafMetricCollector).wafErrorCode(-127);
    verify(ctx, times(2)).isWafContextClosed();
    assertFalse(flow.isBlocking());
  }

  @Test
  void timeoutIsHonoredWaf() throws Exception {
    // setup
    injectSysConfig(APPSEC_WAF_TIMEOUT, "1");
    WAFModule.createLimitsObject();
    try {
      initialRuleAdd();
      DataBundle db = userAgentBundle("Arachni/v" + repeat('a', 4000));
      ChangeableFlow flow = new ChangeableFlow();

      TraceSegment segment = mock(TraceSegment.class);
      List<TraceSegmentPostProcessor> postProcessors = service.getTraceSegmentPostProcessors();
      TraceSegmentPostProcessor pp = postProcessors.get(postProcessors.size() - 1);
      nextBlock();

      // when
      dataListener.onDataAvailable(flow, ctx, db, gwCtx);

      // then
      assertFalse(flow.isBlocking());
      verify(ctx).isWafContextClosed();
      verify(ctx).getWafContextIfReady(true, false);
      verify(ctx).getOrCreateWafContext(any(), eq(true), eq(false));
      verify(ctx, times(2)).getWafMetrics();
      verify(ctx).increaseWafTimeouts();
      verifyNoMoreMockInteractions(segment);

      // when
      pp.processTraceSegment(segment, ctx, emptyList());

      // then
      verify(segment).setTagTop("_dd.appsec.waf.timeouts", 1);
    } finally {
      // cleanup
      removeSysConfig(APPSEC_WAF_TIMEOUT);
      WAFModule.createLimitsObject();
    }
  }

  @Test
  void timeoutIsHonoredRasp() throws Exception {
    // setup
    injectSysConfig(APPSEC_WAF_TIMEOUT, "1");
    WAFModule.createLimitsObject();
    try {
      initialRuleAdd();
      DataBundle db = userAgentBundle("Arachni/v" + repeat('a', 4000));
      ChangeableFlow flow = new ChangeableFlow();

      TraceSegment segment = mock(TraceSegment.class);
      List<TraceSegmentPostProcessor> postProcessors = service.getTraceSegmentPostProcessors();
      TraceSegmentPostProcessor pp = postProcessors.get(postProcessors.size() - 1);

      gwCtx = new GatewayContext(false, RuleType.SQL_INJECTION);
      nextBlock();

      // when
      dataListener.onDataAvailable(flow, ctx, db, gwCtx);

      // then
      assertFalse(flow.isBlocking());
      verify(ctx).getWafContextIfReady(true, true);
      verify(ctx).getOrCreateWafContext(any(), eq(true), eq(true));
      verify(ctx).isWafContextClosed();
      verify(ctx).getRaspMetrics();
      verify(ctx).getRaspMetricsCounter();
      verify(ctx).increaseRaspTimeouts();
      verify(wafMetricCollector).raspTimeout(gwCtx.raspRuleType);
      verify(wafMetricCollector).raspRuleEval(RuleType.SQL_INJECTION);
      verifyNoMoreMockInteractions(segment);

      // when
      pp.processTraceSegment(segment, ctx, emptyList());

      // then
      verify(segment).setTagTop("_dd.appsec.rasp.timeout", 1);
    } finally {
      // cleanup
      removeSysConfig(APPSEC_WAF_TIMEOUT);
      WAFModule.createLimitsObject();
      gwCtx = new GatewayContext(false);
    }
  }

  @Test
  void configurationCanBeGivenLater() throws Exception {
    // when / then
    assertThrows(
        RuntimeException.class,
        () ->
            initialRuleAddWithMap(
                Collections.<String, Object>singletonMap(
                    "waf", new BadConfig()))); // empty configs are allowed now
    verifyNoMoreMockInteractions();
    nextBlock();

    // when
    // default config
    initialRuleAdd();
    dataListener.onDataAvailable(mock(ChangeableFlow.class), ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    verify(ctx).getWafContextIfReady(true, false);
    verify(ctx).getOrCreateWafContext(any(), eq(true), eq(false));
    verify(ctx).reportEvents(anyCollection());
    verify(ctx).isWafContextClosed();
    verify(ctx, times(2)).getWafMetrics();
    verify(ctx).isThrottled(null);
    verifyTraceKept();
    verify(ctx).closeWafContext();
    verify(tracer, times(2)).activeSpan();
    verifyWafInit();
    verifyNoMoreMockInteractions();
  }

  @Test
  void ruleDataGivenThroughConfiguration() throws Exception {
    initialRuleAdd();
    ChangeableFlow flow = mock(ChangeableFlow.class);
    String ipData = readResource("waf_module/ip_with_expiration_data.json");
    doReturn(false).when(ctx).isWafContextClosed();
    nextBlock();

    // when
    send("my_config", ipData);
    wafModule.applyConfig(reconf);
    DataBundle bundle = MapDataBundle.of(REQUEST_INFERRED_CLIENT_IP, "1.2.3.4");
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyConfigUpdated();
    verifyWafRunAndContextClose();
    verify(tracer, times(2)).activeSpan();
    verifyEventReported();
    verify(flow).setAction(argThat(Flow.Action::isBlocking));
    verify(flow).isBlocking();
    verifyNoMoreMockInteractions(flow);
  }

  @Test
  void reloadingRulesClearsWafDataAndRuleToggling() throws Exception {
    initialRuleAdd();
    ChangeableFlow flow = mock(ChangeableFlow.class);
    String ipData = readResource("waf_module/ip_data_with_conditions.json");
    doReturn(false).when(ctx).isWafContextClosed();
    stubWafContextCreation();
    stubWafContextClosing();
    nextBlock();

    // when: reconfigure with data and toggling
    send("my_config", ipData);
    send("my_config", toggleById("ip_match_rule", false));

    wafModule.applyConfig(reconf);
    DataBundle bundle = MapDataBundle.of(REQUEST_INFERRED_CLIENT_IP, "1.2.3.4");
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then: no match; rule is disabled
    verifyConfigUpdated();
    verifyWafRunAndContextClose();
    verifyAnyTimeouts();
    verifyNoMoreMockInteractions(flow);
    nextBlock(flow);

    // when: removing data and override config
    service.getWafBuilder().removeConfig("my_config");
    wafModule.applyConfig(reconf);

    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then: no match; data was cleared (though rule is no longer disabled)
    verifyWafRunAndContextClose();
    verifyConfigUpdated();
    verifyAnyTimeouts();
    verifyNoMoreMockInteractions(flow);
    nextBlock(flow);

    // when: data is read
    send("my_config", ipData);
    wafModule.applyConfig(reconf);
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then: now we have match
    verifyConfigUpdated();
    verifyWafRunAndContextClose();
    verify(tracer, times(2)).activeSpan();
    verifyEventReported();
    verify(flow).setAction(argThat(Flow.Action::isBlocking));
    verify(flow).isBlocking();
    verifyAnyTimeouts();
    verifyNoMoreMockInteractions(flow);
    nextBlock(flow);

    // when: toggling the rule off
    send("my_config", toggleById("ip_match_rule", false));
    wafModule.applyConfig(reconf);
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then: nothing again; we disabled the rule
    verifyConfigUpdated();
    verifyWafRunAndContextClose();
    verifyAnyTimeouts();
    verifyNoMoreMockInteractions(flow);
  }

  @Test
  void ruleTogglingDataGivenThroughConfiguration() throws Exception {
    ChangeableFlow flow = mock(ChangeableFlow.class);
    initialRuleAdd();
    doReturn(false).when(ctx).isWafContextClosed();
    stubWafContextCreation();
    stubWafContextClosing();
    nextBlock();

    // when: rule disabled in config b
    send("b", toggleById("ua0-600-12x", false));
    wafModule.applyConfig(reconf);
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    verify(wafMetricCollector).wafUpdates(isNull(), eq(true));
    verify(reconf).reloadSubscriptions();
    // no attack
    verifyWafRunAndContextClose();
    verifyAnyTimeouts();
    verifyNoMoreMockInteractions(flow);
    nextBlock(flow);

    // when: rule enabled in config a has no effect
    send("a", toggleById("ua0-600-12x", true));
    wafModule.applyConfig(reconf);
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    verifyConfigUpdated();
    // no attack
    verifyWafRunAndContextClose();
    verifyAnyTimeouts();
    verifyNoMoreMockInteractions(flow);
    nextBlock(flow);

    // when: rule enabled in config c overrides b
    send("b", null);
    send("c", toggleById("ua0-600-12x", true));
    wafModule.applyConfig(reconf);
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    verifyConfigUpdated();
    // attack found
    verifyWafRunAndContextClose();
    verify(flow).isBlocking();
    verify(flow).setAction(argThat(Flow.Action::isBlocking));
    verify(tracer, times(2)).activeSpan();
    verifyEventReported();
    verifyAnyTimeouts();
    verifyNoMoreMockInteractions(flow);
    nextBlock(flow);

    // when: removing c and a removes c and a, allows earlier toggle to take effect
    send("b", toggleById("ua0-600-12x", false));
    send("c", null);
    send("a", null);
    wafModule.applyConfig(reconf);
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    verifyConfigUpdated();
    // no attack
    verifyWafRunAndContextClose();
    verifyAnyTimeouts();
    verifyNoMoreMockInteractions(flow);
  }

  @Test
  void initialConfigurationHasUnknownAddresses() throws Exception {
    Address<String> doesNotExistAddress =
        AddressTestBridge.newAddress("server.request.headers.does-not-exist");

    // when
    initialRuleAdd("waf_module/unknown_address_config.json");

    // then
    verifyWafInit();
    assertFalse(
        wafModule
            .getDataSubscriptions()
            .iterator()
            .next()
            .getSubscribedAddresses()
            .contains(doesNotExistAddress));
    verifyNoMoreMockInteractions();
  }

  @Test
  void badInitialConfigurationIsGivenResultsInNoSubscriptions() {
    // when
    assertThrows(
        AppSecModuleActivationException.class,
        () -> initialRuleAddWithMap(Collections.<String, Object>singletonMap("waf", emptyMap())));

    // then
    assertTrue(wafModule.getDataSubscriptions().isEmpty());
    verify(wafMetricCollector).wafInit(eq(Waf.LIB_VERSION), any(), eq(false));
    verifyNoMoreMockInteractions();
  }

  @Test
  void ruleDataNotAConfig() {
    // when
    assertThrows(
        AppSecModuleActivationException.class,
        () -> initialRuleAddWithMap(Collections.<String, Object>singletonMap("waf", emptyMap())));

    // then
    assertNull(wafModule.ctxAndAddresses.get());
    // WAF initialization is attempted but fails, so wafInit is called with success=false
    verify(wafMetricCollector).wafInit(eq(Waf.LIB_VERSION), any(), eq(false));
    verifyNoMoreMockInteractions();
  }

  @Test
  void badResultWithDataEmptyList() {
    WAFModule waf = new WAFModule();
    Waf.ResultWithData rwd = new Waf.ResultWithData(null, "[]", null, null, false, 0, false);

    // when
    Collection<AppSecEvent> ret = waf.buildEvents(rwd, null);

    // then
    assertTrue(ret.isEmpty());
  }

  @Test
  void badResultWithDataEmptyObject() {
    WAFModule waf = new WAFModule();
    Waf.ResultWithData rwd = new Waf.ResultWithData(null, "[{}]", null, null, false, 0, false);

    // when
    Collection<AppSecEvent> ret = waf.buildEvents(rwd, null);

    // then
    assertTrue(ret.isEmpty());
  }

  @Test
  void ephemeralAndPersistentAddresses() throws Exception {
    initialRuleAdd();
    wafModule.applyConfig(reconf);
    ChangeableFlow flow = mock(ChangeableFlow.class);
    doReturn(false).when(ctx).isWafContextClosed();
    // do not record the events in the spied context, they are captured and checked below
    doAnswer(invocation -> null).when(ctx).reportEvents(anyCollection());
    nextBlock();

    // when
    DataBundle transientBundle = MapDataBundle.of(REQUEST_BODY_OBJECT, "/cybercop");
    dataListener.onDataAvailable(flow, ctx, transientBundle, gwCtx);

    // then
    verify(ctx).getWafContextIfReady(true, false);
    verify(ctx).getOrCreateWafContext(any(), eq(true), eq(false));
    verify(tracer, times(2)).activeSpan();
    AppSecEvent firstEvent = verifyReportedEvent();
    assertEquals("/cybercop", firstEvent.getRuleMatches().get(0).parameters.get(0).value);
    verify(ctx, times(2)).getWafMetrics();
    verify(flow).isBlocking();
    verify(ctx).isThrottled(null);
    verifyTraceKept();
    verify(ctx).isWafContextClosed();
    verifyNoMoreMockInteractions(flow);
    nextBlock(flow);

    // when
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
    ctx.closeWafContext();

    // then
    // the context created by the first call is reused without going through creation
    verify(ctx).getWafContextIfReady(true, false);
    verify(ctx, never()).getOrCreateWafContext(any(), anyBoolean(), anyBoolean());
    verify(flow).setAction(argThat(Flow.Action::isBlocking));
    verify(tracer, times(2)).activeSpan();
    AppSecEvent secondEvent = verifyReportedEvent();
    assertEquals("Arachni/v0", secondEvent.getRuleMatches().get(0).parameters.get(0).value);
    verify(ctx, times(2)).getWafMetrics();
    verify(ctx).isWafContextClosed();
    verify(ctx).closeWafContext();
    verify(ctx).isThrottled(null);
    verifyTraceKept();
    verify(flow).isBlocking();
    verifyNoMoreMockInteractions(flow);
  }

  /**
   * This test simulates double REQUEST_END with increasing interval The race condition shouldn't
   * happen when closing WafContext
   */
  @RepeatedTest(value = 3, name = "test repeated {currentRepetition} time")
  void parallelRequestEndShouldNotCauseRaceCondition() throws Exception {
    initialRuleAdd();
    ChangeableFlow flow = new ChangeableFlow();
    AppSecRequestContext ctx = new AppSecRequestContext();

    // when / then
    // java.lang.IllegalStateException: This WafContext is no longer online
    // Should not be thrown
    assertDoesNotThrow(() -> {
      for (int t = 0; t < 20; t++) {
        CountDownLatch latch = new CountDownLatch(1);
        dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx);
        Thread thread =
            new Thread(() -> {
              latch.countDown();
              ctx.closeWafContext();
            });
        thread.start();
        latch.await();
        Thread.sleep(t);
        ctx.close();
        thread.join();
      }
    });
  }

  @Test
  @WithConfig(key = APPSEC_TRACE_RATE_LIMIT, value = "5")
  void honorsAppsecTraceRateLimit() {
    // setup
    Monitoring monitoring = mock(Monitoring.class);

    // when
    WAFModule waf = new WAFModule(monitoring);

    // then
    assertEquals(5, RateLimiterTestBridge.getLimitPerSec(waf.rateLimiter));
  }

  @Test
  void suspiciousAttackerBlocking() throws Exception {
    // given
    ChangeableFlow flow = mock(ChangeableFlow.class);
    String suspiciousIp = "34.65.27.85";
    initialRuleAdd("rules_suspicious_attacker_blocking.json");
    wafModule.applyConfig(reconf);

    DataBundle bundle =
        MapDataBundle.of(
            REQUEST_INFERRED_CLIENT_IP,
            suspiciousIp,
            HEADERS_NO_COOKIES,
            headers("user-agent", singletonList("Arachni/v1.5.1")));
    stubWafContextCreation();
    nextBlock();

    // when
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verifyEventReported();
    verify(tracer, times(2)).activeSpan();
    verify(flow).isBlocking();
    verify(flow, never()).setAction(any());
    verifyNoMoreMockInteractions(flow);
    nextBlock(flow);

    // when
    String ipData =
        "{\"exclusion_data\": [{"
            + "\"id\": \"suspicious_ips_data_id\","
            + " \"type\": \"ip_with_expiration\","
            + " \"data\": [{\"value\": \""
            + suspiciousIp
            + "\"}]}]}";
    doReturn(false).when(ctx).isWafContextClosed();
    send("suspicious_ips", ipData);
    wafModule.applyConfig(reconf);
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyConfigUpdated();
    verify(flow).setAction(argThat(blockingAction(402, BlockingContentType.AUTO)));
    verify(flow).isBlocking();
    verifyWafRunAndContextClose();
    verifyEventReported();
    verify(tracer, times(2)).activeSpan();
    verifyNoMoreMockInteractions(flow);
  }

  @Test
  void httpEndpointFingerprintSupport() throws Exception {
    // given
    ChangeableFlow flow = mock(ChangeableFlow.class);
    String fingerprint = "_dd.appsec.fp.http.endpoint";
    initialRuleAdd("fingerprint_config.json");
    Map<Address<?>, Object> data = new HashMap<>();
    data.put(WAF_CONTEXT_PROCESSOR, singletonMap("fingerprint", true));
    data.put(REQUEST_METHOD, "GET");
    data.put(REQUEST_URI_RAW, "http://localhost:8080/test");
    data.put(REQUEST_BODY_OBJECT, emptyMap());
    data.put(REQUEST_QUERY, singletonMap("name", singletonList("test")));
    data.put(HEADERS_NO_COOKIES, headers("user-agent", singletonList("Arachni/v1.5.1")));
    DataBundle bundle = MapDataBundle.ofDelegate(data);
    wafModule.applyConfig(reconf);

    // when
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verify(flow).setAction(argThat(Flow.Action::isBlocking));
    verify(ctx).reportDerivatives(argThat(derivativeMatches(fingerprint, "http-get-.*")));
  }

  @Test
  void httpSessionFingerprintSupport() throws Exception {
    // given
    ChangeableFlow flow = mock(ChangeableFlow.class);
    String fingerprint = "_dd.appsec.fp.session";
    String sessionId = UUID.randomUUID().toString();
    initialRuleAdd("fingerprint_config.json");
    wafModule.applyConfig(reconf);
    Map<Address<?>, Object> data = new HashMap<>();
    data.put(WAF_CONTEXT_PROCESSOR, singletonMap("fingerprint", true));
    data.put(REQUEST_COOKIES, singletonMap("JSESSIONID", singletonList(sessionId)));
    data.put(SESSION_ID, sessionId);
    data.put(USER_ID, "admin");
    DataBundle bundle = MapDataBundle.ofDelegate(data);

    // when
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verify(ctx).reportDerivatives(argThat(derivativeMatches(fingerprint, "ssn-.*")));
  }

  @Test
  void retrieveUsedAddresses() throws Exception {
    // when
    initialRuleAdd("small_config.json");
    wafModule.applyConfig(reconf);
    WAFModule.CtxAndAddresses ctx0 = wafModule.ctxAndAddresses.get();
    Collection<Address<?>> addresses = ctx0.addressesOfInterest;

    // then
    assertEquals(6, addresses.size());
    assertTrue(addresses.contains(REQUEST_INFERRED_CLIENT_IP));
    assertTrue(addresses.contains(REQUEST_QUERY));
    assertTrue(addresses.contains(REQUEST_PATH_PARAMS));
    assertTrue(addresses.contains(HEADERS_NO_COOKIES));
    assertTrue(addresses.contains(REQUEST_URI_RAW));
    assertTrue(addresses.contains(REQUEST_BODY_OBJECT));
  }

  @Test
  void wafNotUsedIfTheContextIsClosed() throws Exception {
    ChangeableFlow flow = mock(ChangeableFlow.class);
    doReturn(true).when(ctx).isWafContextClosed();

    // when
    initialRuleAdd("rules_with_data_config.json");
    dataListener = wafModule.getDataSubscriptions().iterator().next();

    DataBundle bundle = MapDataBundle.of(USER_ID, "legit-user");
    ctx.closeWafContext();
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);

    // then
    verify(ctx).closeWafContext();
    verify(ctx).isWafContextClosed();
    verifyWafInit();
    verifyNoMoreMockInteractions(flow);
  }

  @Test
  void raspRuleSkippedIfRaspAvailableAndWafContextIsClosed() throws Exception {
    // setup
    ChangeableFlow flow = mock(ChangeableFlow.class);
    GatewayContext gwCtxMock = new GatewayContext(false, RuleType.SQL_INJECTION);
    doReturn(true).when(ctx).isWafContextClosed();

    // when
    initialRuleAdd("rules_with_data_config.json");
    dataListener = wafModule.getDataSubscriptions().iterator().next();

    DataBundle bundle = MapDataBundle.of(USER_ID, "legit-user");
    ctx.closeWafContext();
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtxMock);

    // then
    verify(ctx).closeWafContext();
    verify(ctx).isWafContextClosed();
    verifyWafInit();
    verify(wafMetricCollector).raspRuleSkipped(RuleType.SQL_INJECTION);
    verifyNoMoreMockInteractions(flow);
  }

  @TableTest({
    "scenario                   | userAgent        | expectedBlocked",
    "blocked=true (blocking)    | RaspBlocking/v1  | true           ",
    "blocked=false (stack only) | RaspStackOnly/v1 | false          "
  })
  void raspRuleMatchReportsBlockedAfterTheActionProcessingLoop(
      String userAgent, boolean expectedBlocked) throws Exception {
    // setup
    // Two RASP-matching rules on the same address: one blocks, the other only asks for a stack
    // trace. The `blocked` flag reported to telemetry must be resolved AFTER the action processing
    // loop has run, since that loop is what turns a match into an actual block (or not).
    GatewayContext raspGwCtx = new GatewayContext(false, RuleType.SQL_INJECTION);

    // when
    initialRuleAdd("waf_module/rasp_blocking_and_stack_only_config.json");
    wafModule.applyConfig(reconf);

    // then
    verifyWafInit();
    verifyConfigUpdated();
    nextBlock();

    // when
    ChangeableFlow flow = new ChangeableFlow();
    DataBundle bundle = userAgentBundle(userAgent);
    dataListener.onDataAvailable(flow, ctx, bundle, raspGwCtx);
    ctx.closeWafContext();

    // then
    assertEquals(expectedBlocked, flow.isBlocking());
    verify(ctx).setRaspMatched(true);
    verify(wafMetricCollector).raspRuleEval(RuleType.SQL_INJECTION);
    verify(wafMetricCollector).raspRuleMatch(RuleType.SQL_INJECTION, expectedBlocked);
    verify(wafMetricCollector, never()).raspRuleMatch(RuleType.SQL_INJECTION, !expectedBlocked);
  }

  @ParameterizedTest(name = "test raspErrorCode metric is increased when waf call throws {0}")
  @EnumSource(WafErrorCode.class)
  void testRaspErrorCodeMetricIsIncreasedWhenWafCallThrows(WafErrorCode wafErrorCode)
      throws Exception {
    // setup
    ChangeableFlow flow = mock(ChangeableFlow.class);
    GatewayContext gwCtxMock = new GatewayContext(false, RuleType.SQL_INJECTION);
    WafContext wafContextMock = mock(WafContext.class);
    // if UnclassifiedWafException it's called twice
    doReturn(false).when(ctx).isWafContextClosed();
    doReturn(wafContextMock).when(ctx).getOrCreateWafContext(any(), eq(true), eq(true));
    when(wafContextMock.run(any(), any(), any())).thenThrow(createWafException(wafErrorCode));

    // when
    initialRuleAdd("rules_with_data_config.json");

    DataBundle bundle = MapDataBundle.of(USER_ID, "legit-user");
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtxMock);

    // then
    verify(ctx, atLeast(1)).isWafContextClosed();
    verify(ctx, atMost(2)).isWafContextClosed();
    verify(ctx).getWafContextIfReady(true, true);
    verify(ctx).getOrCreateWafContext(any(), eq(true), eq(true));
    verify(wafMetricCollector).raspRuleEval(RuleType.SQL_INJECTION);
    verify(wafContextMock).run(any(), any(), any());
    verifyWafInit();
    verify(ctx).getRaspMetrics();
    verify(ctx).getRaspMetricsCounter();
    verify(wafMetricCollector).raspErrorCode(eq(RuleType.SQL_INJECTION), anyInt());
    verifyNoMoreMockInteractions(flow, wafContextMock);
  }

  @ParameterizedTest(name = "test wafErrorCode metric is increased when waf call throws {0}")
  @EnumSource(WafErrorCode.class)
  void testWafErrorCodeMetricIsIncreasedWhenWafCallThrows(WafErrorCode wafErrorCode)
      throws Exception {
    // setup
    ChangeableFlow flow = mock(ChangeableFlow.class);
    WafContext wafContextMock = mock(WafContext.class);
    // if UnclassifiedWafException it's called twice
    doReturn(false).when(ctx).isWafContextClosed();
    doReturn(wafContextMock).when(ctx).getOrCreateWafContext(any(), eq(true), eq(false));
    when(wafContextMock.run(any(), any(), any())).thenThrow(createWafException(wafErrorCode));

    // when
    initialRuleAdd("rules_with_data_config.json");

    DataBundle bundle = MapDataBundle.of(USER_ID, "legit-user");
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);

    // then
    verify(ctx, atLeast(1)).isWafContextClosed();
    verify(ctx, atMost(2)).isWafContextClosed();
    verify(ctx).getWafContextIfReady(true, false);
    verify(ctx).getOrCreateWafContext(any(), eq(true), eq(false));
    verify(wafContextMock).run(any(), any(), any());
    verifyWafInit();
    verify(ctx, times(2)).getWafMetrics();
    verify(wafMetricCollector).wafErrorCode(anyInt());
    verify(ctx).setWafErrors();
    verifyNoMoreMockInteractions(flow, wafContextMock);
  }

  @Test
  void internalApiWafErrorCodeEnumMatchesLibddwafJavaByNameAndCode() {
    // given
    Map<String, Integer> libddwaf = new HashMap<>();
    for (WafErrorCode code : WafErrorCode.values()) {
      libddwaf.put(code.name(), code.getCode());
    }
    Map<String, Integer> internal = new HashMap<>();
    for (WafMetricCollector.WafErrorCode code : WafMetricCollector.WafErrorCode.values()) {
      internal.put(code.name(), code.getCode());
    }

    // expect
    assertEquals(libddwaf, internal);
  }

  @Test
  void resultWithDataNullData() {
    WAFModule waf = new WAFModule();
    Waf.ResultWithData rwd = new Waf.ResultWithData(null, null, null, null, false, 0, false);

    // when
    Collection<AppSecEvent> ret = assertDoesNotThrow(() -> waf.buildEvents(rwd, null));

    // then
    assertTrue(ret.isEmpty());
  }

  @Test
  void testRulesCompatWithOutputAttributes() throws Exception {
    // when
    initialRuleAdd("waf_module/rules_compat_config.json");
    wafModule.applyConfig(reconf);

    // then
    verifyWafInit();
    verifyConfigUpdated();
    verifyNoMoreMockInteractions();
    nextBlock();
    stubWafContextCreation();
    doReturn(metrics).when(ctx).getWafMetrics();
    doReturn(false).when(ctx).isWafContextClosed();

    // when: test rules_compat rule with attributes, no keep and no event
    DataBundle bundle1 = userAgentBundle("RulesCompat/v1");
    ChangeableFlow flow1 = new ChangeableFlow();
    dataListener.onDataAvailable(flow1, ctx, bundle1, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verify(ctx).reportDerivatives(traceAttributes("RulesCompat/v1", 123456789));
    // libddwaf 2.0.1: ResultWithData.events now reflects the real "events" array, so
    // attributes-only matches don't call reportEvents().
    verify(ctx, never()).reportEvents(any());
    verifyNoMoreInteractions(ctx);
    assertFalse(flow1.isBlocking());
    nextBlock();

    // when: test rules_compat rule with attributes, keep and no event
    DataBundle bundle2 = userAgentBundle("RulesCompat/v2");
    ChangeableFlow flow2 = new ChangeableFlow();
    dataListener.onDataAvailable(flow2, ctx, bundle2, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verify(ctx).reportDerivatives(traceAttributes("RulesCompat/v2", 987654321));
    verify(ctx).isThrottled(null);
    verifyTraceKept();
    // See comment on the previous scenario: event:false means no entry in the "events" array.
    verify(ctx, never()).reportEvents(any());
    verifyNoMoreInteractions(ctx);
    assertFalse(flow2.isBlocking());
    nextBlock();

    // when: test rules_compat rule with attributes, keep and event
    DataBundle bundle3 = userAgentBundle("RulesCompat/v3");
    ChangeableFlow flow3 = new ChangeableFlow();
    dataListener.onDataAvailable(flow3, ctx, bundle3, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verify(ctx).reportDerivatives(traceAttributes("RulesCompat/v3", 555666777));
    verifyEventReported();
    verifyNoMoreInteractions(ctx);
    assertFalse(flow3.isBlocking());
    nextBlock();

    // when: test rate-limited rules_compat rule with attributes, keep and event
    doReturn(true).when(ctx).isThrottled(null);
    ChangeableFlow flow4 = new ChangeableFlow();
    dataListener.onDataAvailable(flow4, ctx, bundle3, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verify(ctx).reportDerivatives(traceAttributes("RulesCompat/v3", 555666777));
    verify(ctx).reportEvents(anyCollection());
    verify(ctx).isThrottled(null);
    verify(ctx).setWafRateLimited();
    verifyNoMoreInteractions(ctx);
    assertFalse(flow4.isBlocking());
  }

  @Test
  void testTraceTaggingRuleWithAttributesNoKeepAndEventDynamicValueExtraction() throws Exception {
    // when
    initialRuleAdd("waf_module/trace_tagging_dynamic_value_config.json");
    wafModule.applyConfig(reconf);

    // then
    verifyWafInit();
    verifyConfigUpdated();
    verifyNoMoreMockInteractions();
    nextBlock();
    doReturn(metrics).when(ctx).getWafMetrics();
    doReturn(false).when(ctx).isWafContextClosed();

    // when: test trace tagging rule with attributes, no keep and event (dynamic value extraction)
    DataBundle bundle = userAgentBundle("TraceTagging/v4");
    ChangeableFlow flow = new ChangeableFlow();
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    // Should report derivatives with dynamic value extraction - the user-agent value should be
    // extracted
    verify(ctx).reportDerivatives(traceAttributes("TraceTagging/v4", 1729));
    verify(ctx).reportEvents(anyCollection());
    verifyNoMoreInteractions(ctx);
    assertFalse(flow.isBlocking()); // Should not block since keep: false
  }

  @Test
  void securityResponseIdIsExtractedFromBlockingActionAndIncludedInRequestBlockingAction()
      throws Exception {
    doReturn(metrics).when(ctx).getWafMetrics();
    doReturn(false).when(ctx).isWafContextClosed();

    // when
    initialRuleAdd("waf_module/security_response_id_block_config.json");
    wafModule.applyConfig(reconf);
    DataBundle bundle = userAgentBundle("BlockTest");
    ChangeableFlow flow = new ChangeableFlow();
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verifyEventReported();
    verify(ctx).setWafBlocked();
    verifyNoMoreInteractions(ctx);
    assertTrue(flow.isBlocking());
    Flow.Action.RequestBlockingAction action =
        assertInstanceOf(Flow.Action.RequestBlockingAction.class, flow.getAction());
    assertEquals(403, action.getStatusCode());
    assertEquals(BlockingContentType.JSON, action.getBlockingContentType());
    // securityResponseId should be extracted from libddwaf (or null if not present)
    // With libddwaf v18.0.0, security_response_id is automatically generated
    // We just verify the field is accessible
    assertNullOrUuid(action.getSecurityResponseId());
  }

  @Test
  void securityResponseIdIsUniqueAcrossMultipleBlockingRequests() throws Exception {
    // when: first blocking request
    initialRuleAdd("waf_module/security_response_id_unique_config.json");
    wafModule.applyConfig(reconf);
    DataBundle bundle1 =
        MapDataBundle.of(HEADERS_NO_COOKIES, headers("x-test-header", "BlockUnique1"));
    ChangeableFlow flow1 = new ChangeableFlow();
    dataListener.onDataAvailable(flow1, ctx, bundle1, gwCtx);
    ctx.closeWafContext();

    // and: second blocking request with fresh context
    AppSecRequestContext ctx2 = spy(new AppSecRequestContext());
    ChangeableFlow flow2 = new ChangeableFlow();
    DataBundle bundle2 =
        MapDataBundle.of(HEADERS_NO_COOKIES, headers("x-test-header", "BlockUnique2"));
    dataListener.onDataAvailable(flow2, ctx2, bundle2, gwCtx);
    ctx2.closeWafContext();

    // then: both requests are blocked
    assertTrue(flow1.isBlocking());
    assertTrue(flow2.isBlocking());

    // and: both have RequestBlockingAction with accessible securityResponseId
    Flow.Action.RequestBlockingAction action1 =
        assertInstanceOf(Flow.Action.RequestBlockingAction.class, flow1.getAction());
    Flow.Action.RequestBlockingAction action2 =
        assertInstanceOf(Flow.Action.RequestBlockingAction.class, flow2.getAction());

    // and: if both securityResponseIds are present, they should be different
    String securityResponseId1 = action1.getSecurityResponseId();
    String securityResponseId2 = action2.getSecurityResponseId();
    // If libddwaf generates securityResponseIds, they should be unique
    if (securityResponseId1 != null && securityResponseId2 != null) {
      assertNotEquals(securityResponseId1, securityResponseId2);
      assertTrue(securityResponseId1.matches(UUID_PATTERN));
      assertTrue(securityResponseId2.matches(UUID_PATTERN));
    }
  }

  @Test
  void requestBlockingActionHandlesNullSecurityResponseIdGracefully() throws Exception {
    // when
    initialRuleAdd("waf_module/security_response_id_null_config.json");
    wafModule.applyConfig(reconf);
    DataBundle bundle = userAgentBundle("NullSecurityResponseIdTest");
    ChangeableFlow flow = new ChangeableFlow();
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    assertTrue(flow.isBlocking());
    Flow.Action.RequestBlockingAction action =
        assertInstanceOf(Flow.Action.RequestBlockingAction.class, flow.getAction());
    assertEquals(418, action.getStatusCode());
    assertEquals(BlockingContentType.HTML, action.getBlockingContentType());
    // securityResponseId may be null or a valid UUID - both are acceptable
    assertNullOrUuid(action.getSecurityResponseId());
  }

  @Test
  void securityResponseIdIsPresentInRedirectActionWithLocationHeader() throws Exception {
    doReturn(metrics).when(ctx).getWafMetrics();
    doReturn(false).when(ctx).isWafContextClosed();

    // when
    initialRuleAdd("waf_module/security_response_id_redirect_config.json");
    wafModule.applyConfig(reconf);
    DataBundle bundle = userAgentBundle("RedirectWithSecurityResponseId");
    ChangeableFlow flow = new ChangeableFlow();
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx);
    ctx.closeWafContext();

    // then
    verifyWafRunAndContextClose();
    verifyEventReported();
    verify(ctx).setWafBlocked();
    verifyNoMoreInteractions(ctx);
    assertTrue(flow.isBlocking());
    Flow.Action.RequestBlockingAction action =
        assertInstanceOf(Flow.Action.RequestBlockingAction.class, flow.getAction());

    // and: redirect has Location header and possibly security_response_id
    assertEquals(302, action.getStatusCode());
    assertEquals(BlockingContentType.NONE, action.getBlockingContentType());
    assertTrue(action.getExtraHeaders().containsKey("Location"));
    String location = action.getExtraHeaders().get("Location");
    assertTrue(location.startsWith("https://example.com/blocked"));

    // securityResponseId should be accessible (may be null or a valid UUID)
    assertNullOrUuid(action.getSecurityResponseId());
  }

  private void send(String configKey, String json) throws IOException {
    ConfigKey config = new TestConfigKey(configKey);
    if (json == null) {
      listener.remove(config, null);
      return;
    }
    listener.accept(config, json.getBytes(UTF_8), null);
  }

  private void initialRuleAdd() throws Exception {
    initialRuleAdd("test_multi_config.json");
  }

  private void initialRuleAdd(String location) throws Exception {
    send("initial_waf", readResource(location));
    configureWafModule();
  }

  private void initialRuleAddWithMap(Map<String, Object> definition) throws Exception {
    send("initial_waf", ADAPTER.toJson(definition));
    configureWafModule();
  }

  private void configureWafModule() throws AppSecModuleActivationException {
    wafModule.setWafBuilder(service.getWafBuilder());
    wafModule.config(cfg);
    dataListener = wafModule.getDataSubscriptions().iterator().next();
  }

  private static String toggleById(String id, boolean enabled) {
    return "{\"rules_override\": [{\"rules_target\": [{\"rule_id\": \""
        + id
        + "\"}], \"enabled\": "
        + enabled
        + "}]}";
  }

  private void stubWafContextCreation() {
    doAnswer(invocation -> wafContext = new WafContext(invocation.getArgument(0)))
        .when(ctx)
        .getOrCreateWafContext(any(WafHandle.class), eq(true), eq(false));
  }

  private void stubWafContextClosing() {
    doAnswer(
            invocation -> {
              wafContext.close();
              wafContext = null;
              return null;
            })
        .when(ctx)
        .closeWafContext();
  }

  /** Verifies a WAF run on the request context, followed by the closing of its WAF context. */
  private void verifyWafRunAndContextClose() {
    verify(ctx).getWafContextIfReady(true, false);
    verify(ctx).getOrCreateWafContext(any(), eq(true), eq(false));
    verify(ctx, times(2)).getWafMetrics();
    verify(ctx).isWafContextClosed();
    verify(ctx).closeWafContext();
  }

  /** Verifies that an event was reported and that the trace was kept. */
  private void verifyEventReported() {
    verify(ctx).reportEvents(anyCollection());
    verify(ctx).isThrottled(null);
    verifyTraceKept();
  }

  /** Verifies that the trace was manually kept and its local root span tagged accordingly. */
  private void verifyTraceKept() {
    verify(ctx).setManuallyKept(true);
    verify(localRootSpan).setTag(Tags.ASM_KEEP, true);
    verify(localRootSpan).setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
  }

  private static StackTraceFrame firstNonReflectionFrame(List<StackTraceFrame> frames) {
    for (StackTraceFrame frame : frames) {
      String className = frame.getClass_name();
      if (!className.startsWith("sun.reflect.")
          && !className.startsWith("jdk.internal.reflect.")
          && !className.startsWith("java.lang.reflect.")) {
        return frame;
      }
    }
    throw new AssertionError("no frame outside of the JDK reflection");
  }

  private AppSecEvent verifyReportedEvent() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<AppSecEvent>> eventsCaptor =
        ArgumentCaptor.forClass(Collection.class);
    verify(ctx).reportEvents(eventsCaptor.capture());
    return eventsCaptor.getValue().iterator().next();
  }

  private void verifyWafInit() {
    verify(wafMetricCollector).wafInit(eq(Waf.LIB_VERSION), any(), eq(true));
  }

  private void verifyConfigUpdated() {
    verify(wafMetricCollector).wafUpdates(any(), eq(true));
    verify(reconf).reloadSubscriptions();
  }

  /** Timeouts may or may not happen depending on the machine speed. */
  private void verifyAnyTimeouts() {
    verify(ctx, atLeast(0)).increaseWafTimeouts();
    verify(ctx, atLeast(0)).increaseRaspTimeouts();
  }

  /** Verifies that no other interaction happened on the mocks of the test. */
  private void verifyNoMoreMockInteractions(Object... otherMocks) {
    verifyNoMoreInteractions(ctx, wafMetricCollector, reconf);
    // the tracer and span stubs can be called any number of times
    verifyNoMoreInteractions(ignoreStubs(tracer, activeSpan, localRootSpan));
    if (otherMocks.length > 0) {
      verifyNoMoreInteractions(otherMocks);
    }
  }

  /** Starts a new when/then block: interactions are only counted within each block. */
  private void nextBlock(Object... otherMocks) {
    clearInvocations(ctx, tracer, activeSpan, localRootSpan, wafMetricCollector, reconf);
    if (otherMocks.length > 0) {
      clearInvocations(otherMocks);
    }
  }

  private static ArgumentMatcher<Flow.Action> blockingAction(
      int statusCode, BlockingContentType blockingContentType) {
    return action ->
        action instanceof Flow.Action.RequestBlockingAction
            && ((Flow.Action.RequestBlockingAction) action).getStatusCode() == statusCode
            && ((Flow.Action.RequestBlockingAction) action).getBlockingContentType()
                == blockingContentType;
  }

  private static ArgumentMatcher<Map<String, Object>> derivativeMatches(
      String key, String valuePattern) {
    return map -> map.containsKey(key) && String.valueOf(map.get(key)).matches(valuePattern);
  }

  /** The WAF attributes, where numbers are decoded as doubles. */
  private static Map<String, Object> traceAttributes(String agent, double integerAttribute) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("_dd.appsec.trace.agent", agent);
    attributes.put("_dd.appsec.trace.integer", integerAttribute);
    return attributes;
  }

  private static void assertNullOrUuid(String securityResponseId) {
    assertTrue(securityResponseId == null || securityResponseId.matches(UUID_PATTERN));
  }

  private static DataBundle userAgentBundle(Object userAgent) {
    return MapDataBundle.of(HEADERS_NO_COOKIES, headers("user-agent", userAgent));
  }

  /** Builds headers whose values do not have to be lists of strings. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static CaseInsensitiveMap<List<String>> headers(String name, Object value) {
    CaseInsensitiveMap headers = new CaseInsensitiveMap<>();
    headers.put(name, value);
    return headers;
  }

  private static String repeat(char character, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, character);
    return new String(chars);
  }

  /** Helper to return a concrete Waf exception for each WafErrorCode */
  private static AbstractWafException createWafException(WafErrorCode code) {
    switch (code) {
      case INVALID_ARGUMENT:
        return new InvalidArgumentWafException(code.getCode());
      case INVALID_OBJECT:
        return new InvalidObjectWafException(code.getCode());
      case INTERNAL_ERROR:
        return new InternalWafException(code.getCode());
      case BINDING_ERROR:
        return new UnclassifiedWafException(code.getCode());
      default:
        throw new IllegalStateException("Unhandled WafErrorCode: " + code);
    }
  }

  /** Config key whose string representation is the given key, as used by the WAF builder. */
  private static final class TestConfigKey implements ConfigKey {
    private final String key;

    TestConfigKey(String key) {
      this.key = key;
    }

    @Override
    public Product getProduct() {
      return Product._UNKNOWN;
    }

    @Override
    public String getProductName() {
      return "null";
    }

    @Override
    public String getOrg() {
      return "null";
    }

    @Override
    public Integer getVersion() {
      return 1;
    }

    @Override
    public String getConfigId() {
      return "null";
    }

    @Override
    public String toString() {
      return key;
    }
  }

  /** List that only supports {@code get}, returning {@code null}: the WAF fails to encode it. */
  private static final class OnlyGetNullList extends AbstractList<Object> {
    @Override
    public Object get(int index) {
      return null;
    }

    @Override
    public int size() {
      throw new UnsupportedOperationException();
    }
  }

  /** Map that fails when iterated. */
  private static final class BadConfig extends AbstractMap<String, Object> {
    @Override
    public Set<Entry<String, Object>> entrySet() {
      throw new IllegalStateException("You tried to iterate!");
    }
  }
}
