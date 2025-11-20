package com.datadog.appsec.ddwaf

import com.datadog.appsec.AppSecModule.AppSecModuleActivationException
import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.config.AppSecConfigService
import com.datadog.appsec.config.AppSecConfigServiceImpl
import com.datadog.appsec.config.AppSecModuleConfigurer
import com.datadog.appsec.config.TraceSegmentPostProcessor
import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.DataListener
import com.datadog.appsec.event.data.Address
import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.gateway.GatewayContext
import com.datadog.appsec.report.AppSecEvent
import com.datadog.ddwaf.Waf
import com.datadog.ddwaf.WafContext
import com.datadog.ddwaf.WafErrorCode
import com.datadog.ddwaf.WafHandle
import com.datadog.ddwaf.WafMetrics
import com.datadog.ddwaf.exception.AbstractWafException
import com.datadog.ddwaf.exception.InternalWafException
import com.datadog.ddwaf.exception.InvalidArgumentWafException
import com.datadog.ddwaf.exception.InvalidObjectWafException
import com.datadog.ddwaf.exception.UnclassifiedWafException
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.appsec.api.blocking.BlockingContentType
import datadog.communication.monitor.Monitoring
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.remoteconfig.state.ConfigKey
import datadog.remoteconfig.state.ParsedConfigKey
import datadog.remoteconfig.state.ProductListener
import datadog.trace.api.Config
import datadog.trace.api.ConfigDefaults
import datadog.trace.api.gateway.Flow
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.telemetry.RuleType
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.api.telemetry.WafMetricCollector.WafErrorCode as InternalWafErrorCode
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.stacktrace.StackTraceEvent
import okio.Okio
import spock.lang.Shared
import spock.lang.Unroll

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP

class WAFModuleSpecification extends DDSpecification {
  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  @Shared
  protected static final ORIGINAL_METRIC_COLLECTOR = WafMetricCollector.get()
  private static final JsonAdapter<Map<String, Object>> ADAPTER =
  new Moshi.Builder()
  .build()
  .adapter(Types.newParameterizedType(Map, String, Object))

  private static final DataBundle ATTACK_BUNDLE = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
  new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v0']))

  protected AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI) {
    activeSpan() >> Mock(AgentSpan) {
      getSpanId() >> 777
      getLocalRootSpan() >> Mock(AgentSpan)
    }
    getSpanId() >> 777
  }

  AppSecRequestContext ctx = Spy()
  GatewayContext gwCtx = new GatewayContext(false)

  AppSecConfigServiceImpl service
  WAFModule wafModule = new WAFModule()
  DataListener dataListener

  WafContext wafContext
  WafMetrics metrics = new WafMetrics()

  WafMetricCollector wafMetricCollector = Mock(WafMetricCollector)
  AppSecConfigService.TransactionalAppSecModuleConfigurer cfg
  ProductListener listener

  AppSecModuleConfigurer.Reconfiguration reconf = Mock()

  void setup() {
    WafMetricCollector.INSTANCE = wafMetricCollector
    AgentTracer.forceRegister(tracer)
    AppSecSystem.active = true

    final configurationPoller = Stub(ConfigurationPoller) {
      addListener(Product.ASM_DD, _ as ProductListener) >> {
        Product _, ProductListener l ->
        listener = l
      }
    }
    service = new AppSecConfigServiceImpl(Config.get(), configurationPoller, () -> {})
    service.init()
    service.maybeSubscribeConfigPolling()
    assert listener != null

    cfg = service.createAppSecModuleConfigurer()
    cfg.commit()
  }

  void cleanup() {
    WafMetricCollector.INSTANCE  = ORIGINAL_METRIC_COLLECTOR
    AgentTracer.forceRegister(ORIGINAL_TRACER)
    service.close()
    wafContext?.close()
  }

  private void send(String configKey, Object map){
    accept(configKey, map as Map<String, Object>)
  }

  private void initialRuleAdd(String location = "test_multi_config.json") {
    def stream = getClass().classLoader.getResourceAsStream(location)
    accept('initial_waf', ADAPTER.fromJson(Okio.buffer(Okio.source(stream))))
    wafModule.setWafBuilder(service.getWafBuilder())
    wafModule.config(cfg)
    dataListener = wafModule.dataSubscriptions.first()
  }

  void initialRuleAddWithMap(Map<String, Object> definition) {
    accept('initial_waf', definition)
    wafModule.setWafBuilder(service.getWafBuilder())
    wafModule.config(cfg)
    dataListener = wafModule.dataSubscriptions.first()
  }

  private void accept(String configKey, Map<String, Object> map) {
    ConfigKey config = new ParsedConfigKey(configKey, 'null', 1, 'null', 'null')
    if(map == null) {
      listener.remove(config, null)
      return
    }
    def json = ADAPTER.toJson(map)
    listener.accept(config, json.getBytes(), null)
  }


  void 'override on_match through reconfiguration'() {
    ChangeableFlow flow = Mock()

    when:
    initialRuleAdd('override_actions_config.json')

    Map<String, Object> actions =
    [actions:
      [
        [
          id: 'block2',
          type: 'block_request',
          parameters: [
            status_code: 501,
            type: 'json'
          ]
        ]
      ]
      ,rules_override:
      [
        [
          rules_target: [[
              rule_id: 'ip_match_rule',
            ],],
          on_match: ['block2']
        ]
      ]]

    Map<String, Object> ipData = [
      rules_data :[
        [
          id  : 'ip_data',
          type: 'data_with_expiration',
          data: [[
              value     : '1.2.3.4',
              expiration: '0',
            ]]
        ]
      ]]

    send('b', actions)
    send('c', ipData)

    wafModule.applyConfig(reconf)
    def newBundle = MapDataBundle.of(KnownAddresses.REQUEST_INFERRED_CLIENT_IP, '1.2.3.4')
    dataListener.onDataAvailable(flow, ctx, newBundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 501 &&
      rba.blockingContentType == BlockingContentType.JSON
    })

    1 * ctx.getOrCreateWafContext(_ as WafHandle, true, false)
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * _
  }

  void 'provide data through the initial config'() {
    ChangeableFlow flow = Mock()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    initialRuleAdd('rules_with_data_config.json')
    def bundle = MapDataBundle.of(
    KnownAddresses.USER_ID,
    'user-to-block-1'
    )
    wafModule.applyConfig(service.reconfiguration)
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
      rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateWafContext(_ as WafHandle, true, false) >> {
      wafContext = new WafContext(it[0])
    }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * _

    when: 'merges new waf data with the one in the rules config'
    def newData = [rules_data: [
        [
          id  : 'blocked_users',
          type: 'data_with_expiration',
          data: [
            [
              value     : 'user-to-block-2',
              expiration: '0',
            ]
          ]
        ]
      ]]
    send('c', newData)
    wafModule.applyConfig(reconf)
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
      rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateWafContext(_ as WafHandle, true, false)
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * _

    when:
    bundle = MapDataBundle.of(
    KnownAddresses.USER_ID,
    'user-to-block-2'
    )
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
      rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateWafContext(_ as WafHandle, true, false)
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * _

    when: 'changes the rules config'
    def newCfg = [
      version: '2.1',
      rules: [
        [
          id: 'block-users',
          name: 'Block User Addresses',
          tags: [
            type: 'block_user',
            category: 'security_response'
          ],
          conditions: [
            [
              parameters: [
                inputs: [[ address: 'usr.id' ]],
                data: 'blocked_users'
              ],
              operator: 'exact_match'
            ]
          ],
          on_match: ['block'] ]
      ], ]
    send('initial_waf', null)
    send('waf', newCfg)
    wafModule.applyConfig(reconf)

    and:
    bundle = MapDataBundle.of(
    KnownAddresses.USER_ID,
    'user-to-block-2'
    )
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
      rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateWafContext(_ as WafHandle, true, false)
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * _

    when:
    bundle = MapDataBundle.of(
    KnownAddresses.USER_ID,
    'user-to-block-1'
    )
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_ as WafHandle, true, false)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    0 * _
  }

  void 'add exclusions through reconfiguration'() {
    ChangeableFlow flow = new ChangeableFlow()

    when:
    initialRuleAdd()
    def exclusions = [ exclusions:
      [
        [
          id          : '1',
          rules_target: [
            [
              tags: [
                type    : 'security_scanner',
              ]
            ]
          ],
          conditions  : [
            [
              operator  : 'exact_match',
              parameters: [
                inputs: [[
                    address: 'http.client_ip'
                  ]],
                list  : ['192.168.0.1']
              ]
            ]
          ]
        ]
      ]
    ]

    send('b', exclusions)
    wafModule.applyConfig(reconf)

    then:
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    0 * _

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * ctx.setWafBlocked()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * _

    when:
    def newBundle = MapDataBundle.of(
    KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v0']),
    KnownAddresses.REQUEST_INFERRED_CLIENT_IP,
    '192.168.0.1'
    )
    dataListener.onDataAvailable(flow, ctx, newBundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    0 * _
  }

  void 'add custom rule through reconfiguration'() {
    ChangeableFlow flow = new ChangeableFlow()

    when:
    initialRuleAdd()

    def customRules = [ custom_rules:
      [
        [
          id: 'ua0-600-12x-copy',
          name: 'Arachni',
          tags: [
            category: 'attack_attempt',
            type: 'security_scanner2'
          ],
          conditions: [
            [
              operator: 'match_regex',
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path:['user-agent']
                  ]
                ],
                regex: '^Arachni/v'
              ]
            ]
          ],
          on_match: ['block']
        ]
      ]
    ]

    send('b', customRules)
    wafModule.applyConfig(reconf)

    then:
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    0 * _

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * tracer.activeSpan()
    1 * ctx.reportEvents({ it.size() == 1 })
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * ctx.setWafBlocked()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * _
  }

  void 'replace actions through runtime configuration'() {
    ChangeableFlow flow = Mock()

    when:
    initialRuleAdd('test_multi_config_no_action.json')

    // original action
    def action1 =  [
      actions:
      [
        [
          id        : 'block',
          type      : 'block_request',
          parameters: [
            status_code: 418,
            type      : 'html'
          ]
        ]
      ]
    ]

    def action2 =  [
      actions:
      [
        [
          id        : 'block',
          type      : 'block_request',
          parameters: [
            status_code: 401
          ]
        ]
      ]
    ]

    send('original config', action1)
    send('original config', null)
    send('new config', action2)
    wafModule.applyConfig(reconf)

    then:
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    0 * _

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    // original rule is replaced; no attack
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 401 &&
      rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * _
  }

  void 'redirect actions are correctly processed expected variant redirect#variant'(int variant, int statusCode) {
    when:
    initialRuleAdd('redirect_actions.json')
    wafModule.applyConfig(reconf)
    DataBundle bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': 'redirect' + variant]))
    def flow = new ChangeableFlow()
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics() >> metrics
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * ctx.reportEvents(_)
    1 * ctx.setWafBlocked()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * ctx._(*_)
    flow.blocking
    flow.action instanceof Flow.Action.RequestBlockingAction
    with(flow.action as Flow.Action.RequestBlockingAction) {
      assert it.statusCode == statusCode
      assert it.extraHeaders == [Location: "https://example${variant}.com/"]
    }

    where:
    variant | statusCode
    1       | 303
    2       | 301
    3       | 303
  }

  void 'is named ddwaf'() {
    expect:
    wafModule.name == 'ddwaf'
  }

  void 'report waf stats on first span'() {
    setup:
    TraceSegment segment = Mock()
    TraceSegmentPostProcessor pp
    initialRuleAdd()

    wafModule.applyConfig(reconf)
    when:
    pp = service.traceSegmentPostProcessors.first()
    pp.processTraceSegment(segment, ctx, [])

    then:
    1 * segment.setTagTop('_dd.appsec.waf.version', _ as String)
    1 * segment.setTagTop('_dd.appsec.event_rules.loaded', 117)
    1 * segment.setTagTop('_dd.appsec.event_rules.error_count', 1)
    1 * segment.setTagTop('_dd.appsec.event_rules.errors', { it =~ /\{"[^"]+":\["bad rule"]}/})
    1 * segment.setTagTop('asm.keep', true)
    0 * segment._(*_)

    when:
    pp.processTraceSegment(segment, ctx, [])

    then:
    0 * segment._(*_)
  }

  void 'triggers a rule through the user agent header'() {
    initialRuleAdd()
    ChangeableFlow flow = new ChangeableFlow()
    wafModule.applyConfig(reconf)

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics() >> metrics
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * ctx.reportEvents(_)
    1 * ctx.setWafBlocked()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * ctx._(*_)
    flow.blocking
    flow.action.statusCode == 418
    flow.action.blockingContentType == BlockingContentType.HTML
  }

  void 'no metrics are set if waf metrics are off'() {
    setup:
    metrics = null
    injectSysConfig('appsec.waf.metrics', 'false')
    wafModule = new WAFModule() // replace the one created too soon
    initialRuleAdd()
    ChangeableFlow flow = new ChangeableFlow()
    wafModule.applyConfig(reconf)

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, false, false)
    2 * ctx.getWafMetrics() >> null
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * ctx.reportEvents(_)
    1 * ctx.setWafBlocked()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * ctx._(*_)
    metrics == null
  }

  void 'reports waf metrics'() {
    setup:
    TraceSegment segment = Mock()
    TraceSegmentPostProcessor pp
    Flow flow = new ChangeableFlow()
    initialRuleAdd()

    when:
    pp = service.traceSegmentPostProcessors[1]
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()
    pp.processTraceSegment(segment, ctx, [])

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    1 * ctx.closeWafContext()
    3 * ctx.getWafMetrics() >> {
      metrics.with {
        totalDdwafRunTimeNs = new AtomicLong(1000)
        totalRunTimeNs = new AtomicLong(2000)
        truncatedStringTooLongCount = new AtomicLong(0)
        truncatedListMapTooLargeCount = new AtomicLong(0)
        truncatedObjectTooDeepCount = new AtomicLong(0)
        it } }

    1 * segment.setTagTop('_dd.appsec.waf.duration', 1)
    1 * segment.setTagTop('_dd.appsec.waf.duration_ext', 2)
    1 * segment.setTagTop('_dd.appsec.event_rules.version', '0.42.0')

    0 * segment._(*_)
  }

  void 'can trigger a nonwafContext waf run'() {
    initialRuleAdd()
    ChangeableFlow flow = new ChangeableFlow()
    wafModule.applyConfig(reconf)

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics() >> metrics
    1 * ctx.reportEvents(*_)
    1 * ctx.setWafBlocked()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    1 * ctx.isWafContextClosed() >> false
    0 * ctx._(*_)
    flow.blocking
  }

  void 'reports events'() {
    setup:
    initialRuleAdd()
    wafModule.applyConfig(reconf)
    AppSecEvent event
    StackTraceEvent stackTrace
    def attackBundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/generate-stacktrace']))

    when:
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, attackBundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, _)
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }
    1 * ctx.reportStackTrace(_ as StackTraceEvent) >> { stackTrace = it[0] }

    event.rule.id == 'generate-stacktrace-on-scanner'
    event.rule.name == 'Arachni'
    event.rule.tags == [type: 'security_scanner', category: 'attack_attempt']

    event.ruleMatches[0].operator == 'match_regex'
    event.ruleMatches[0].operator_value == '^Arachni\\/generate-stacktrace'
    event.ruleMatches[0].parameters[0].address == 'server.request.headers.no_cookies'
    event.ruleMatches[0].parameters[0].highlight == ['Arachni/generate-stacktrace']
    event.ruleMatches[0].parameters[0].key_path == ['user-agent']
    event.ruleMatches[0].parameters[0].value == 'Arachni/generate-stacktrace'

    event.spanId == 777

    stackTrace.language == 'java'
    stackTrace.message == 'Exploit detected'
    stackTrace.frames.size() >= 1
    stackTrace.frames[0].class_name == 'org.codehaus.groovy.vmplugin.v8.IndyInterface' // With Groovy 3 it was 'org.codehaus.groovy.runtime.callsite.CallSiteArray'
    stackTrace.frames[0].function == 'fromCache' // With Groovy 3 it was 'defaultCall'

  }

  void 'redaction with default settings'() {
    initialRuleAdd()
    AppSecEvent event

    when:
    def bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': [password: 'Arachni/v0']]))
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, _)
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters[0].address == 'server.request.headers.no_cookies'
    event.ruleMatches[0].parameters[0].highlight == ['<Redacted>']
    event.ruleMatches[0].parameters[0].key_path == ['user-agent', 'password']
    event.ruleMatches[0].parameters[0].value == '<Redacted>'
  }

  void 'disabling of key regex'() {
    injectSysConfig(APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP, '')
    setup()
    initialRuleAdd()
    AppSecEvent event

    when:
    def bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': [password: 'Arachni/v0']]))
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters[0].address == 'server.request.headers.no_cookies'
    event.ruleMatches[0].parameters[0].highlight == ['Arachni/v']
    event.ruleMatches[0].parameters[0].key_path == ['user-agent', 'password']
    event.ruleMatches[0].parameters[0].value == 'Arachni/v0'
  }

  void 'redaction of values'() {
    injectSysConfig(APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP, 'Arachni')
    setup()
    initialRuleAdd()
    AppSecEvent event

    when:
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, _)
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters[0].address == 'server.request.headers.no_cookies'
    event.ruleMatches[0].parameters[0].highlight == ['<Redacted>']
    event.ruleMatches[0].parameters[0].key_path == ['user-agent']
    event.ruleMatches[0].parameters[0].value == '<Redacted>'
  }

  void 'triggers no rule'() {
    initialRuleAdd()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': 'Harmless']))

    when:
    dataListener.onDataAvailable(flow, ctx, db, gwCtx)

    then:
    1 * ctx.getOrCreateWafContext(_, true, _)
    !flow.blocking
  }

  void 'non-string types work'() {
    initialRuleAdd()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.REQUEST_BODY_OBJECT,
    [
      [key: [
          true,
          (byte)1,
          (short)2,
          (int)3,
          (long)4,
          (float)5.0,
          (double)6.0,
          (char)'7',
          (BigDecimal)8.0G,
          (BigInteger)9.0G
        ]]
    ])

    when:
    dataListener.onDataAvailable(flow, ctx, db, gwCtx)

    then:
    1 * ctx.getOrCreateWafContext(_, true, _)
    !flow.blocking
  }

  void 'waf exceptions do not propagate'() {
    initialRuleAdd()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES, [get: { null }] as List)

    when:
    dataListener.onDataAvailable(flow, ctx, db, gwCtx)

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics()
    1 * ctx.setWafErrors()
    1 * wafMetricCollector.wafErrorCode(-127)
    2 * ctx.isWafContextClosed()
    assert !flow.blocking
  }

  void 'timeout is honored (waf)'() {
    setup:
    injectSysConfig('appsec.waf.timeout', '1')
    WAFModule.createLimitsObject()
    initialRuleAdd()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v' + ('a' * 4000)]))
    ChangeableFlow flow = new ChangeableFlow()

    TraceSegment segment = Mock()
    TraceSegmentPostProcessor pp = service.traceSegmentPostProcessors.last()

    when:
    dataListener.onDataAvailable(flow, ctx, db, gwCtx)

    then:
    assert !flow.blocking
    1 * ctx.isWafContextClosed()
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics()
    1 * ctx.increaseWafTimeouts()
    0 * _

    when:
    pp.processTraceSegment(segment, ctx, [])

    then:
    1 * segment.setTagTop('_dd.appsec.waf.timeouts', 1L)
    _ * segment.setTagTop(_, _)

    cleanup:
    injectSysConfig('appsec.waf.timeout', ConfigDefaults.DEFAULT_APPSEC_WAF_TIMEOUT as String)
    WAFModule.createLimitsObject()
  }

  void 'timeout is honored (rasp)'() {
    setup:
    injectSysConfig('appsec.waf.timeout', '1')
    WAFModule.createLimitsObject()
    initialRuleAdd()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v' + ('a' * 4000)]))
    ChangeableFlow flow = new ChangeableFlow()

    TraceSegment segment = Mock()
    TraceSegmentPostProcessor pp = service.traceSegmentPostProcessors.last()

    gwCtx = new GatewayContext(false, RuleType.SQL_INJECTION)

    when:
    dataListener.onDataAvailable(flow, ctx, db, gwCtx)

    then:
    assert !flow.blocking
    1 * ctx.getOrCreateWafContext(_, true, true)
    1 * ctx.isWafContextClosed()
    1 * ctx.getRaspMetrics()
    1 * ctx.getRaspMetricsCounter()
    1 * ctx.increaseRaspTimeouts()
    1 * wafMetricCollector.get().raspTimeout(gwCtx.raspRuleType)
    1 * wafMetricCollector.raspRuleEval(RuleType.SQL_INJECTION)
    0 * _

    when:
    pp.processTraceSegment(segment, ctx, [])

    then:
    1 * segment.setTagTop('_dd.appsec.rasp.timeout', 1L)
    _ * segment.setTagTop(_, _)

    cleanup:
    injectSysConfig('appsec.waf.timeout', ConfigDefaults.DEFAULT_APPSEC_WAF_TIMEOUT as String)
    WAFModule.createLimitsObject()
    gwCtx = new GatewayContext(false)
  }

  void 'configuration can be given later'() {
    when:
    initialRuleAddWithMap([waf: new BadConfig()]) // empty configs are allowed now

    then:
    thrown RuntimeException
    0 * _

    when:
    // default config
    initialRuleAdd()
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.isWafContextClosed()
    2 * ctx.getWafMetrics()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    1 * ctx.closeWafContext()
    2 * tracer.activeSpan()
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    0 * _
  }

  void 'rule data given through configuration'() {
    initialRuleAdd()
    ChangeableFlow flow = Mock()
    def ipData = [ rules_data :
      [
        [
          id  : 'ip_data',
          type: 'ip_with_expiration',
          data: [[
              value     : '1.2.3.4',
              expiration: '0',
            ]]
        ]
      ]
    ]

    when:
    send('my_config', ipData)
    wafModule.applyConfig(reconf)
    def bundle = MapDataBundle.of(KnownAddresses.REQUEST_INFERRED_CLIENT_IP, '1.2.3.4')
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    2 * ctx.getWafMetrics()
    1 * flow.setAction({ it.blocking })
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * _
  }

  private static Object toggleById(String id, boolean enabled) {
    [ rules_override:
      [
        [
          rules_target: [[
              rule_id: id
            ]],
          enabled: enabled
        ]
      ]
    ]
  }

  void 'reloading rules clears waf data and rule toggling'() {
    initialRuleAdd()
    ChangeableFlow flow = Mock()
    def ipData = [
      rules_data :
      [
        [
          id  : 'ip_data',
          name: 'IP data',
          conditions: [
            [
              parameters: [
                inputs: [[ address: 'http.client_ip' ]],
                data: 'blocked_users'
              ],
              operator: 'exact_match'
            ]
          ],
          tags: [
            type: 'test',
            category: 'test',
            confidence: '1',
          ],
          type: 'ip_with_expiration',
          on_match: ['block'],
          data: [[
              value     : '1.2.3.4',
              expiration: '0',
            ]]
        ]
      ]
    ]

    when: 'reconfigure with data and toggling'
    send('my_config', ipData)
    send('my_config', toggleById('ip_match_rule', false))

    wafModule.applyConfig(reconf)
    def bundle = MapDataBundle.of(KnownAddresses.REQUEST_INFERRED_CLIENT_IP, '1.2.3.4')
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then: 'no match; rule is disabled'
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _

    when: 'removing data and override config'
    service.getWafBuilder().removeConfig("my_config")
    wafModule.applyConfig(reconf)

    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then: 'no match; data was cleared (though rule is no longer disabled)'
    1 * ctx.getOrCreateWafContext(_, true, false)
    1 * ctx.isWafContextClosed() >> false
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * ctx.closeWafContext()
    2 * ctx.getWafMetrics()
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _

    when: 'data is read'
    send('my_config', ipData)
    wafModule.applyConfig(reconf)
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then: 'now we have match'
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    2 * ctx.getWafMetrics()
    1 * flow.setAction({ it.blocking })
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _

    when: 'toggling the rule off'
    send('my_config', toggleById('ip_match_rule', false))
    wafModule.applyConfig(reconf)
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then: 'nothing again; we disabled the rule'
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _
  }

  void 'rule toggling data given through configuration'() {
    ChangeableFlow flow = Mock()
    initialRuleAdd()
    WafContext wafContext

    when: 'rule disabled in config b'
    send('b', toggleById('ua0-600-12x', false))
    wafModule.applyConfig(reconf)
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * wafMetricCollector.wafUpdates(null, true)
    1 * reconf.reloadSubscriptions()
    // no attack
    1 * ctx.getOrCreateWafContext(_, true, false) >> {
      WafHandle wafHandle = it[0] as WafHandle
      wafContext = new WafContext(wafHandle)}
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext() >> {
      wafContext.close()
    }
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _

    when: 'rule enabled in config a has no effect'
    send('a', toggleById('ua0-600-12x', true))
    wafModule.applyConfig(reconf)
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    // no attack
    1 * ctx.getOrCreateWafContext(_, true, false) >> {
      WafHandle wafHandle = it[0] as WafHandle
      wafContext = new WafContext(wafHandle)}
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext() >> {
      wafContext.close()
    }
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _

    when: 'rule enabled in config c overrides b'
    send('b', null)
    send('c', toggleById('ua0-600-12x', true))
    wafModule.applyConfig(reconf)
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    // attack found
    1 * ctx.getOrCreateWafContext(_, true, false) >> {
      WafHandle wafHandle = it[0] as WafHandle
      wafContext = new WafContext(wafHandle)}
    2 * ctx.getWafMetrics()
    1 * flow.isBlocking()
    1 * flow.setAction({ it.blocking })
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext() >> {
      wafContext.close()
    }
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * _

    when: 'removing c and a removes c and a, allows earlier toggle to take effect'
    send('b', toggleById('ua0-600-12x', false))
    send('c', null)
    send('a', null)
    wafModule.applyConfig(reconf)
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    // no attack
    1 * ctx.getOrCreateWafContext(_, true, false) >> {
      WafHandle wafHandle = it[0] as WafHandle
      wafContext = new WafContext(wafHandle)}
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext() >> {
      wafContext.close()
    }
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _
  }

  void 'initial configuration has unknown addresses'() {
    Address<String> doesNotExistAddress = new Address<>("server.request.headers.does-not-exist")
    def waf =
    [
      version: '2.1',
      rules: [
        [
          id: 'ua0-600-12x',
          name: 'Arachni',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: doesNotExistAddress.key,
                    key_path: ['user-agent']]
                ],
                regex: '^Arachni\\/v'
              ],
              operator: 'match_regex'
            ]
          ],
        ]
      ]
    ]


    when:
    initialRuleAddWithMap(waf as Map<String, Object>)

    then:
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    !wafModule.dataSubscriptions.first().subscribedAddresses.contains(doesNotExistAddress)
    0 * _
  }

  void 'bad initial configuration is given results in no subscriptions'() {
    def waf = [waf: [:]]

    when:
    initialRuleAddWithMap(waf)

    then:
    thrown AppSecModuleActivationException
    wafModule.dataSubscriptions.empty
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, false)
    0 * _
  }

  void 'rule data not a config'() {
    Map<String, Object> waf = [waf: [:]]

    when:
    initialRuleAddWithMap(waf)

    then:
    thrown AppSecModuleActivationException
    wafModule.ctxAndAddresses.get() == null
    // WAF initialization is attempted but fails, so wafInit is called with success=false
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, false)
    0 * _
  }

  void 'bad ResultWithData - empty list'() {
    def waf = new WAFModule()
    Waf.ResultWithData rwd = new Waf.ResultWithData(null, "[]", null, null, false, 0, false)
    Collection ret

    when:
    ret = waf.buildEvents(rwd)

    then:
    ret.isEmpty()
  }

  void 'bad ResultWithData - empty object'() {
    def waf = new WAFModule()
    Waf.ResultWithData rwd = new Waf.ResultWithData(null, "[{}]", null, null, false, 0, false)
    Collection ret

    when:
    ret = waf.buildEvents(rwd)

    then:
    ret.isEmpty()
  }

  void 'ephemeral and persistent addresses'() {
    initialRuleAdd()
    wafModule.applyConfig(reconf)
    ChangeableFlow flow = Mock()

    when:
    def transientBundle = MapDataBundle.of(
    KnownAddresses.REQUEST_BODY_OBJECT,
    '/cybercop'
    )
    dataListener.onDataAvailable(flow, ctx, transientBundle, gwCtx)

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>) >> {
      it[0].iterator().next().ruleMatches[0].parameters[0].value == '/cybercop'
    }
    2 * ctx.getWafMetrics()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    1 * ctx.isWafContextClosed() >> false
    0 * _

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    1 * flow.setAction({ it.blocking })
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>) >> {
      it[0].iterator().next().ruleMatches[0].parameters[0].value == 'user-to-block-1'
    }
    2 * ctx.getWafMetrics()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    1 * flow.isBlocking()
    0 * _
  }

  /**
   * This test simulates double REQUEST_END with increasing interval
   * The race condition shouldn't happen when closing WafContext
   */
  @Unroll("test repeated #n time")
  void 'parallel REQUEST_END should not cause race condition'() {
    initialRuleAdd()
    ChangeableFlow flow = new ChangeableFlow()
    AppSecRequestContext ctx = new AppSecRequestContext()

    when:
    for (int t = 0; t < 20; t++) {
      CountDownLatch latch = new CountDownLatch(1)
      dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
      Thread thread = new Thread({ p ->
        latch.countDown()
        ctx.closeWafContext()
      })
      thread.start()
      latch.await()
      sleep(t)
      ctx.close()
      thread.join()
    }

    then:
    // java.lang.IllegalStateException: This WafContext is no longer online
    // Should not be thrown
    noExceptionThrown()

    where:
    n << (1..3)
  }

  void 'honors appsec.trace.rate.limit'() {
    setup:
    injectSysConfig('dd.appsec.trace.rate.limit', '5')
    def monitoring = Mock(Monitoring)

    when:
    def waf = new WAFModule(monitoring)

    then:
    waf.rateLimiter.limitPerSec == 5

  }

  void 'suspicious attacker blocking'() {
    given:
    final flow = Mock(ChangeableFlow)
    final suspiciousIp = '34.65.27.85'
    initialRuleAdd('rules_suspicious_attacker_blocking.json')
    wafModule.applyConfig(reconf)

    final bundle = MapDataBundle.of(
    KnownAddresses.REQUEST_INFERRED_CLIENT_IP,
    suspiciousIp,
    KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': ['Arachni/v1.5.1']]))

    when:
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_ as WafHandle, true, false)
    2 * ctx.getWafMetrics()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.closeWafContext()
    1 * ctx.isWafContextClosed()
    2 * tracer.activeSpan()
    1 * flow.isBlocking()
    0 * flow.setAction(_)
    0 * _

    when:
    final ipData = [exclusion_data : [
        [
          id  : 'suspicious_ips_data_id',
          type: 'ip_with_expiration',
          data: [[value: suspiciousIp]]
        ]
      ]]
    send('suspicious_ips', ipData)
    wafModule.applyConfig(reconf)
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 402 && rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * flow.isBlocking()
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.getOrCreateWafContext(_ as WafHandle, true, false)
    2 * ctx.getWafMetrics()
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.closeWafContext()
    2 * tracer.activeSpan()
    0 * _
  }

  void 'http endpoint fingerprint support'() {
    given:
    final flow = Mock(ChangeableFlow)
    final fingerprint = '_dd.appsec.fp.http.endpoint'
    initialRuleAdd 'fingerprint_config.json'
    ctx.closeWafContext()
    final bundle = MapDataBundle.ofDelegate([
      (KnownAddresses.WAF_CONTEXT_PROCESSOR): [fingerprint: true],
      (KnownAddresses.REQUEST_METHOD): 'GET',
      (KnownAddresses.REQUEST_URI_RAW): 'http://localhost:8080/test',
      (KnownAddresses.REQUEST_BODY_OBJECT): [:],
      (KnownAddresses.REQUEST_QUERY): [name: ['test']],
      (KnownAddresses.HEADERS_NO_COOKIES): new CaseInsensitiveMap<List<String>>(['user-agent': ['Arachni/v1.5.1']])
    ])
    wafModule.applyConfig(reconf)

    when:
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * flow.setAction({ it.blocking })
    1 * ctx.reportDerivatives({ Map<String, String> map ->
      map.containsKey(fingerprint) && map.get(fingerprint).matches('http-get-.*')
    })
  }

  void 'http session fingerprint support'() {
    given:
    final flow = Mock(ChangeableFlow)
    final fingerprint = '_dd.appsec.fp.session'
    final sessionId = UUID.randomUUID().toString()
    initialRuleAdd 'fingerprint_config.json'
    wafModule.applyConfig(reconf)
    ctx.closeWafContext()
    final bundle = MapDataBundle.ofDelegate([
      (KnownAddresses.WAF_CONTEXT_PROCESSOR): [fingerprint: true],
      (KnownAddresses.REQUEST_COOKIES): [JSESSIONID: [sessionId]],
      (KnownAddresses.SESSION_ID): sessionId,
      (KnownAddresses.USER_ID): 'admin',
    ])

    when:
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.reportDerivatives({ Map<String, String> map ->
      map.containsKey(fingerprint) && map.get(fingerprint).matches('ssn-.*')
    })
  }

  void 'retrieve used addresses'() {
    when:
    initialRuleAdd('small_config.json')
    wafModule.applyConfig(reconf)
    def ctx0 = wafModule.ctxAndAddresses.get()
    def addresses = ctx0.addressesOfInterest

    then:
    addresses.size() == 6
    addresses.contains(KnownAddresses.REQUEST_INFERRED_CLIENT_IP)
    addresses.contains(KnownAddresses.REQUEST_QUERY)
    addresses.contains(KnownAddresses.REQUEST_PATH_PARAMS)
    addresses.contains(KnownAddresses.HEADERS_NO_COOKIES)
    addresses.contains(KnownAddresses.REQUEST_URI_RAW)
    addresses.contains(KnownAddresses.REQUEST_BODY_OBJECT)
  }

  void 'waf not used if the context is closed'() {
    ChangeableFlow flow = Mock()

    when:
    initialRuleAdd('rules_with_data_config.json')
    dataListener = wafModule.dataSubscriptions.first()

    def bundle = MapDataBundle.of(
    KnownAddresses.USER_ID,
    'legit-user'
    )
    ctx.closeWafContext()
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)

    then:
    1 * ctx.closeWafContext()
    1 * ctx.isWafContextClosed() >> true
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    0 * _
  }

  void 'raspRuleSkipped if rasp available and WAF context is closed'() {
    setup:
    ChangeableFlow flow = Mock()
    GatewayContext gwCtxMock = new GatewayContext(false, RuleType.SQL_INJECTION)

    when:
    initialRuleAdd('rules_with_data_config.json')
    dataListener = wafModule.dataSubscriptions.first()

    def bundle = MapDataBundle.of(
    KnownAddresses.USER_ID,
    'legit-user'
    )
    ctx.closeWafContext()
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtxMock)

    then:
    1 * ctx.closeWafContext()
    1 * ctx.isWafContextClosed() >> true
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    1 * wafMetricCollector.raspRuleSkipped(RuleType.SQL_INJECTION)
    0 * _
  }

  void 'test raspErrorCode metric is increased when waf call throws #wafErrorCode '() {
    setup:
    ChangeableFlow flow = Mock()
    GatewayContext gwCtxMock = new GatewayContext(false, RuleType.SQL_INJECTION)
    WafContext wafContext = Mock()

    when:
    initialRuleAdd('rules_with_data_config.json')

    def bundle = MapDataBundle.of(
    KnownAddresses.USER_ID,
    'legit-user'
    )
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtxMock)

    then:
    (1..2) * ctx.isWafContextClosed() >> false // if UnclassifiedWafException it's called twice
    1 * ctx.getOrCreateWafContext(_, true, true) >> wafContext
    1 * wafMetricCollector.raspRuleEval(RuleType.SQL_INJECTION)
    1 * wafContext.run(_, _, _) >> { throw createWafException(wafErrorCode as WafErrorCode) }
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    1 * ctx.getRaspMetrics()
    1 * ctx.getRaspMetricsCounter()
    1 * wafMetricCollector.raspErrorCode(RuleType.SQL_INJECTION, _)
    0 * _

    where:
    wafErrorCode << WafErrorCode.values()
  }

  void 'test wafErrorCode metric is increased when waf  call throws #wafErrorCode '() {
    setup:
    ChangeableFlow flow = Mock()
    WafContext wafContext = Mock()

    when:
    initialRuleAdd('rules_with_data_config.json')

    def bundle = MapDataBundle.of(
    KnownAddresses.USER_ID,
    'legit-user'
    )
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)

    then:
    (1..2) * ctx.isWafContextClosed() >> false // if UnclassifiedWafException it's called twice
    1 * ctx.getOrCreateWafContext(_, true, false) >> wafContext
    1 * wafContext.run(_, _, _) >> { throw createWafException(wafErrorCode as WafErrorCode) }
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    2 * ctx.getWafMetrics()
    1 * wafMetricCollector.wafErrorCode(_)
    1 * ctx.setWafErrors()
    0 * _

    where:
    wafErrorCode << WafErrorCode.values()
  }

  def 'internal-api WafErrorCode enum matches libddwaf-java by name and code'() {
    given:
    def libddwaf = WafErrorCode.values().collectEntries { [it.name(), it.code] }
    def internal = InternalWafErrorCode.values().collectEntries { [it.name(), it.code] }

    expect:
    internal == libddwaf
  }

  void 'ResultWithData - null data'() {
    def waf = new WAFModule()
    Waf.ResultWithData rwd = new Waf.ResultWithData(null, null, null, null, false, 0, false)
    Collection ret

    when:
    ret = waf.buildEvents(rwd)

    then:
    noExceptionThrown()
    ret.isEmpty()
  }

  /**
   * Helper to return a concrete Waf exception for each WafErrorCode
   */
  static AbstractWafException createWafException(WafErrorCode code) {
    switch (code) {
      case WafErrorCode.INVALID_ARGUMENT:
      return new InvalidArgumentWafException(code.code)
      case WafErrorCode.INVALID_OBJECT:
      return new InvalidObjectWafException(code.code)
      case WafErrorCode.INTERNAL_ERROR:
      return new InternalWafException(code.code)
      case WafErrorCode.BINDING_ERROR:
      return new UnclassifiedWafException(code.code)
      default:
      throw new IllegalStateException("Unhandled WafErrorCode: $code")
    }
  }

  void 'test rules_compat with output attributes'() {
    setup:
    def rulesConfig = [
      version: '2.1',
      metadata: [
        rules_version: '1.2.7'
      ],
      rules: [
        [
          id: 'arachni_rule',
          name: 'Arachni',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^Arachni\\/v'
              ],
              operator: 'match_regex'
            ]
          ],
          transformers: [],
          on_match: ['block']
        ]
      ],
      rules_compat: [
        [
          id: 'rc-000-001',
          name: 'Rules Compat Test: Attributes, No Keep, No Event',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^RulesCompat\\/v1'
              ],
              operator: 'match_regex'
            ]
          ],
          output: [
            event: false,
            keep: false,
            attributes: [
              '_dd.appsec.trace.integer': [
                value: 123456789
              ],
              '_dd.appsec.trace.agent': [
                value: 'RulesCompat/v1'
              ]
            ]
          ],
          on_match: []
        ],
        [
          id: 'rc-000-002',
          name: 'Rules Compat Test: Attributes, Keep, No Event',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^RulesCompat\\/v2'
              ],
              operator: 'match_regex'
            ]
          ],
          output: [
            event: false,
            keep: true,
            attributes: [
              '_dd.appsec.trace.integer': [
                value: 987654321
              ],
              '_dd.appsec.trace.agent': [
                value: 'RulesCompat/v2'
              ]
            ]
          ],
          on_match: []
        ],
        [
          id: 'rc-000-003',
          name: 'Rules Compat Test: Attributes, Keep, Event',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^RulesCompat\\/v3'
              ],
              operator: 'match_regex'
            ]
          ],
          output: [
            event: true,
            keep: true,
            attributes: [
              '_dd.appsec.trace.integer': [
                value: 555666777
              ],
              '_dd.appsec.trace.agent': [
                value: 'RulesCompat/v3'
              ]
            ]
          ],
          on_match: []
        ]
      ]
    ]

    when:
    initialRuleAddWithMap(rulesConfig)
    wafModule.applyConfig(reconf)

    then:
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    0 * _

    when: 'test rules_compat rule with attributes, no keep and no event'
    def bundle1 = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': 'RulesCompat/v1']))
    def flow1 = new ChangeableFlow()
    dataListener.onDataAvailable(flow1, ctx, bundle1, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics() >> metrics
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * ctx.reportDerivatives(['_dd.appsec.trace.agent':'RulesCompat/v1', '_dd.appsec.trace.integer': 123456789])
    1 * ctx.isThrottled(null)
    1 * ctx.reportEvents([])
    0 * ctx._(*_)
    !flow1.blocking

    when: 'test rules_compat rule with attributes, keep and no event'
    def bundle2 = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': 'RulesCompat/v2']))
    def flow2 = new ChangeableFlow()
    dataListener.onDataAvailable(flow2, ctx, bundle2, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics() >> metrics
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * ctx.reportDerivatives(['_dd.appsec.trace.agent':'RulesCompat/v2', '_dd.appsec.trace.integer': 987654321])
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    1 * ctx.reportEvents([])
    0 * ctx._(*_)
    !flow2.blocking

    when: 'test rules_compat rule with attributes, keep and event'
    def bundle3 = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': 'RulesCompat/v3']))
    def flow3 = new ChangeableFlow()
    dataListener.onDataAvailable(flow3, ctx, bundle3, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics() >> metrics
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    1 * ctx.reportDerivatives(['_dd.appsec.trace.agent':'RulesCompat/v3', '_dd.appsec.trace.integer': 555666777])
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.isThrottled(null)
    1 * ctx.setManuallyKept(true)
    0 * ctx._(*_)
    !flow3.blocking
  }

  void 'test trace tagging rule with attributes, no keep and event (dynamic value extraction)'() {
    setup:
    def rulesConfig = [
      version: '2.1',
      metadata: [
        rules_version: '1.2.7'
      ],
      rules: [
        [
          id: 'arachni_rule',
          name: 'Arachni',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^Arachni\\/v'
              ],
              operator: 'match_regex'
            ]
          ],
          transformers: [],
          on_match: ['block']
        ]
      ],
      rules_compat: [
        [
          id: 'ttr-000-004',
          name: 'Trace Tagging Rule: Attributes, No Keep, Event',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: 'server.request.headers.no_cookies',
                    key_path: ['user-agent']
                  ]
                ],
                regex: '^TraceTagging\\/v4'
              ],
              operator: 'match_regex'
            ]
          ],
          output: [
            event: true,
            keep: false,
            attributes: [
              '_dd.appsec.trace.integer': [
                value: 1729
              ],
              '_dd.appsec.trace.agent': [
                address: 'server.request.headers.no_cookies',
                key_path: ['user-agent']
              ]
            ]
          ],
          on_match: []
        ]
      ]
    ]

    when:
    initialRuleAddWithMap(rulesConfig)
    wafModule.applyConfig(reconf)

    then:
    1 * wafMetricCollector.wafInit(Waf.LIB_VERSION, _, true)
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    0 * _

    when: 'test trace tagging rule with attributes, no keep and event (dynamic value extraction)'
    def bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
    new CaseInsensitiveMap<List<String>>(['user-agent': 'TraceTagging/v4']))
    def flow = new ChangeableFlow()
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeWafContext()

    then:
    1 * ctx.getOrCreateWafContext(_, true, false)
    2 * ctx.getWafMetrics() >> metrics
    1 * ctx.isWafContextClosed() >> false
    1 * ctx.closeWafContext()
    // Should report derivatives with dynamic value extraction - the user-agent value should be extracted
    1 * ctx.reportDerivatives(['_dd.appsec.trace.agent':'TraceTagging/v4', '_dd.appsec.trace.integer': 1729])
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.isThrottled(null)
    0 * ctx._(*_)
    !flow.blocking // Should not block since keep: false
  }

  private static class BadConfig implements Map<String, Object> {
    @Delegate
    private Map<String, Object> delegate

    @Override
    Set entrySet() {
      throw new IllegalStateException("You tried to iterate!")
    }
  }
}
