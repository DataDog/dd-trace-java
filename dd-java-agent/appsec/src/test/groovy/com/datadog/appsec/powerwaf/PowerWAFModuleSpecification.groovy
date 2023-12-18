package com.datadog.appsec.powerwaf

import com.datadog.appsec.AppSecModule
import com.datadog.appsec.config.AppSecConfig
import com.datadog.appsec.config.AppSecModuleConfigurer
import com.datadog.appsec.config.AppSecUserConfig
import com.datadog.appsec.config.CurrentAppSecConfig
import com.datadog.appsec.config.TraceSegmentPostProcessor
import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.DataListener
import com.datadog.appsec.event.data.Address
import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.report.AppSecEvent
import com.datadog.appsec.report.Parameter
import com.datadog.appsec.test.StubAppSecConfigService
import datadog.trace.api.ConfigDefaults
import datadog.trace.api.internal.TraceSegment
import datadog.appsec.api.blocking.BlockingContentType
import datadog.trace.api.gateway.Flow
import datadog.trace.test.util.DDSpecification
import io.sqreen.powerwaf.Additive
import io.sqreen.powerwaf.Powerwaf
import io.sqreen.powerwaf.PowerwafContext
import io.sqreen.powerwaf.PowerwafMetrics
import spock.lang.Unroll

import java.util.concurrent.CountDownLatch

import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP
import static org.hamcrest.Matchers.hasSize

class PowerWAFModuleSpecification extends DDSpecification {
  private static final DataBundle ATTACK_BUNDLE = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
  new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v0']))

  AppSecRequestContext ctx = Mock()

  StubAppSecConfigService service
  PowerWAFModule pwafModule = new PowerWAFModule()
  DataListener dataListener

  Additive pwafAdditive
  PowerwafMetrics metrics

  void cleanup() {
    pwafAdditive?.close()
    release pwafModule
  }

  private static void release(PowerWAFModule pwafModule) {
    pwafModule?.ctxAndAddresses?.get()?.ctx?.close()
  }

  private void setupWithStubConfigService(String location = "test_multi_config.json") {
    service = new StubAppSecConfigService(location)
    service.init()
    pwafModule.config(service)
    dataListener = pwafModule.dataSubscriptions.first()
  }

  void 'use default actions if none defined in config'() {
    when:
    setupWithStubConfigService'no_actions_config.json'

    then:
    pwafModule.ctxAndAddresses.get().actionInfoMap.size() == 1
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block') != null
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block').parameters == [
      status_code: 403,
      type:'auto',
      grpc_status_code: 10
    ]
  }

  void 'override default actions by config'() {
    when:
    setupWithStubConfigService('override_actions_config.json')

    then:
    pwafModule.ctxAndAddresses.get().actionInfoMap.size() == 1
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block') != null
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block').parameters == [
      status_code: 500,
      type:'html',
    ]
  }

  void 'override actions through reconfiguration'() {
    when:
    setupWithStubConfigService('override_actions_config.json')

    def actions = [
      [
        id: 'block',
        type: 'block_request',
        parameters: [
          status_code: 501,
          type: 'json'
        ]
      ]
    ]
    AppSecModuleConfigurer.Reconfiguration reconf = Stub()
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('b', [], actions, [], []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    then:
    pwafModule.ctxAndAddresses.get().actionInfoMap.size() == 1
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block') != null
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block').parameters == [
      status_code: 501,
      type: 'json',
    ]
  }

  void 'override on_match through reconfiguration'() {
    ChangeableFlow flow = Mock()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    setupWithStubConfigService('override_actions_config.json')
    dataListener = pwafModule.dataSubscriptions.first()

    def actions = [
      [
        id: 'block2',
        type: 'block_request',
        parameters: [
          status_code: 501,
          type: 'json'
        ]
      ]
    ]
    def ruleOverrides = [
      [
        rules_target: [[
            rule_id: 'ip_match_rule',
          ],],
        on_match: ['block2']
      ]
    ]
    def ipData = [
      [
        id  : 'ip_data',
        type: 'ip_with_expiration',
        data: [[
            value     : '1.2.3.4',
            expiration: '0',
          ]]
      ]
    ]
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('b', ruleOverrides, actions, [], []))
      mergedAsmData.addConfig('c', ipData)
      it.dirtyStatus.data = true
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    def newBundle = MapDataBundle.of(
      KnownAddresses.REQUEST_INFERRED_CLIENT_IP,
      '1.2.3.4'
      )
    dataListener.onDataAvailable(flow, ctx, newBundle, false)
    ctx.closeAdditive()

    then:
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 501 &&
        rba.blockingContentType == BlockingContentType.JSON
    })
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    0 * _
  }

  void 'provide data through the initial config'() {
    ChangeableFlow flow = Mock()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    setupWithStubConfigService('rules_with_data_config.json')
    dataListener = pwafModule.dataSubscriptions.first()
    ctx.closeAdditive()

    def bundle = MapDataBundle.of(
      KnownAddresses.USER_ID,
      'user-to-block-1'
      )
    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then:
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
        rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    2 * ctx.closeAdditive()
    1 * flow.isBlocking()
    0 * _

    when: 'merges new waf data with the one in the rules config'
    def newData = [
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
    ]
    service.currentAppSecConfig.with {
      mergedAsmData.addConfig('c', newData)
      it.dirtyStatus.data = true

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then:
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
        rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    0 * _

    when:
    bundle = MapDataBundle.of(
      KnownAddresses.USER_ID,
      'user-to-block-2'
      )
    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then:
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
        rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
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

    service.currentAppSecConfig.with {
      setDdConfig(AppSecConfig.valueOf(newCfg))
      dirtyStatus.markAllDirty()

      service.listeners['waf'].onNewSubconfig(it, reconf)
      dirtyStatus.clearDirty()
    }

    and:
    bundle = MapDataBundle.of(
      KnownAddresses.USER_ID,
      'user-to-block-2'
      )
    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then:
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
        rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    0 * _

    when:
    bundle = MapDataBundle.of(
      KnownAddresses.USER_ID,
      'user-to-block-1'
      )
    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    0 * _
  }

  void 'add exclusions through reconfiguration'() {
    ChangeableFlow flow = new ChangeableFlow()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    setupWithStubConfigService()

    def exclusions = [
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

    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('b', [], [], exclusions, []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    then:
    1 * reconf.reloadSubscriptions()

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive() >> { pwafAdditive.close() }
    0 * _

    when:
    def newBundle = MapDataBundle.of(
      KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v0']),
      KnownAddresses.REQUEST_INFERRED_CLIENT_IP,
      '192.168.0.1'
      )
    dataListener.onDataAvailable(flow, ctx, newBundle, false)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    0 * _
  }

  void 'add custom rule through reconfiguration'() {
    ChangeableFlow flow = new ChangeableFlow()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    setupWithStubConfigService()

    def customRules = [
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

    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('b', [], [], [], customRules))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    then:
    1 * reconf.reloadSubscriptions()

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    // we get two events: one for origin rule, and one for the custom one
    1 * ctx.reportEvents(hasSize(2))
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    0 * _
  }

  void 'append actions in addition to default'() {
    when:
    PowerWAFModule powerWAFModule = new PowerWAFModule()
    StubAppSecConfigService confService = new StubAppSecConfigService("another_actions_config.json")
    confService.init()
    powerWAFModule.config(confService)

    then:
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.size() == 2
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('block') != null
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('block').parameters == [
      status_code: 403,
      type:'auto',
      grpc_status_code: 10
    ]
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('test') != null
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('test').parameters == [
      status_code: 302,
      type:'xxx'
    ]

    cleanup:
    release powerWAFModule
  }

  void 'replace actions through runtime configuration'() {
    ChangeableFlow flow = Mock()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    setupWithStubConfigService()
    // first initialization to exercise the update path
    service.listeners['waf'].onNewSubconfig(service.currentAppSecConfig, reconf)
    service.currentAppSecConfig.dirtyStatus.clearDirty()

    def actions = [
      [
        id: 'block',
        type: 'block_request',
        parameters: [
          status_code: 401,
        ]
      ]
    ]
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('new config', [], actions, [], []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    then:
    1 * reconf.reloadSubscriptions()

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    // original rule is replaced; no attack
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 401 &&
        rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    0 * _
  }

  void 'redirect actions are correctly processed expected variant redirect#variant'(int variant, int statusCode) {
    when:
    setupWithStubConfigService('redirect_actions.json')
    DataBundle bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'redirect' + variant]))
    def flow = new ChangeableFlow()
    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> {
      PowerwafContext pwCtx = it[0] as PowerwafContext
      pwafAdditive = pwCtx.openAdditive()
      metrics = pwCtx.createMetrics()
      pwafAdditive
    }
    1 * ctx.getWafMetrics() >> metrics
    1 * ctx.closeAdditive()
    1 * ctx.reportEvents(_)
    0 * ctx._(*_)
    flow.blocking == true
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

  void 'is named powerwaf'() {
    expect:
    pwafModule.name == 'powerwaf'
  }

  void 'report waf stats on first span'() {
    setup:
    TraceSegment segment = Mock()
    TraceSegmentPostProcessor pp

    when:
    setupWithStubConfigService()
    pp = service.traceSegmentPostProcessors.first()
    pp.processTraceSegment(segment, ctx, [])

    then:
    1 * segment.setTagTop('_dd.appsec.waf.version', _ as String)
    1 * segment.setTagTop('_dd.appsec.event_rules.loaded', 115)
    1 * segment.setTagTop('_dd.appsec.event_rules.error_count', 1)
    1 * segment.setTagTop('_dd.appsec.event_rules.errors', { it =~ /\{"[^"]+":\["bad rule"\]\}/})
    1 * segment.setTagTop('manual.keep', true)
    0 * segment._(*_)

    when:
    pp.processTraceSegment(segment, ctx, [])

    then:
    0 * segment._(*_)
  }

  void 'triggers a rule through the user agent header'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> {
      PowerwafContext pwCtx = it[0] as PowerwafContext
      pwafAdditive = pwCtx.openAdditive()
      metrics = pwCtx.createMetrics()
      pwafAdditive
    }
    1 * ctx.getWafMetrics() >> metrics
    1 * ctx.closeAdditive()
    1 * ctx.reportEvents(_)
    0 * ctx._(*_)
    flow.blocking == true
    flow.action.statusCode == 418
    flow.action.blockingContentType == BlockingContentType.HTML
  }

  void 'no metrics are set if waf metrics are off'() {
    setup:
    injectSysConfig('appsec.waf.metrics', 'false')
    pwafModule = new PowerWAFModule() // replace the one created too soon
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.getWafMetrics() >> null
    1 * ctx.closeAdditive()
    1 * ctx.reportEvents(_)
    0 * ctx._(*_)
    metrics == null
  }

  void 'reports waf metrics'() {
    setup:
    TraceSegment segment = Mock()
    TraceSegmentPostProcessor pp
    Flow flow = new ChangeableFlow()

    when:
    setupWithStubConfigService()
    pp = service.traceSegmentPostProcessors[1]
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()
    pp.processTraceSegment(segment, ctx, [])

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> {
      PowerwafContext pwCtx = it[0] as PowerwafContext
      pwafAdditive = pwCtx.openAdditive()
      metrics = pwCtx.createMetrics()
      pwafAdditive
    }
    1 * ctx.closeAdditive()
    2 * ctx.getWafMetrics() >> { metrics.with { totalDdwafRunTimeNs = 1000; totalRunTimeNs = 2000; it} }

    1 * segment.setTagTop('_dd.appsec.waf.duration', 1)
    1 * segment.setTagTop('_dd.appsec.waf.duration_ext', 2)
    1 * segment.setTagTop('_dd.appsec.event_rules.version', '0.42.0')

    0 * segment._(*_)
  }

  void 'can trigger a nonadditive waf run'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> {
      PowerwafContext pwCtx = it[0] as PowerwafContext
      pwafAdditive = pwCtx.openAdditive()
      metrics = pwCtx.createMetrics()
      pwafAdditive
    }
    1 * ctx.getWafMetrics() >> metrics
    1 * ctx.reportEvents(*_)
    0 * ctx._(*_)
    flow.blocking == true
  }

  void 'reports events'() {
    setupWithStubConfigService()
    AppSecEvent event

    when:
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }

    event.rule.id == 'ua0-600-12x'
    event.rule.name == 'Arachni'
    event.rule.tags == [type: 'security_scanner', category: 'attack_attempt']

    event.ruleMatches[0].operator == 'match_regex'
    event.ruleMatches[0].operatorValue == '^Arachni\\/v'
    event.ruleMatches[0].parameters == [
      new Parameter.Builder()
      .withAddress('server.request.headers.no_cookies')
      .withKeyPath(['user-agent'])
      .withValue('Arachni/v0')
      .withHighlight(['Arachni/v'])
      .build()
    ]
  }

  void 'redaction with default settings'() {
    setupWithStubConfigService()
    AppSecEvent event

    when:
    def bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': [password: 'Arachni/v0']]))
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, bundle, false)
    ctx.closeAdditive()

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters == [
      new Parameter.Builder()
      .withAddress('server.request.headers.no_cookies')
      .withKeyPath(['user-agent', 'password'])
      .withValue('<Redacted>')
      .withHighlight(['<Redacted>'])
      .build()
    ]
  }

  void 'disabling of key regex'() {
    injectSysConfig(APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP, '')
    setupWithStubConfigService()
    AppSecEvent event

    when:
    def bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': [password: 'Arachni/v0']]))
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, bundle, false)
    ctx.closeAdditive()

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters == [
      new Parameter.Builder()
      .withAddress('server.request.headers.no_cookies')
      .withKeyPath(['user-agent', 'password'])
      .withValue('Arachni/v0')
      .withHighlight(['Arachni/v'])
      .build()
    ]
  }

  void 'redaction of values'() {
    injectSysConfig(APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP, 'Arachni')

    setupWithStubConfigService()
    AppSecEvent event

    when:
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters == [
      new Parameter.Builder()
      .withAddress('server.request.headers.no_cookies')
      .withKeyPath(['user-agent'])
      .withValue('<Redacted>')
      .withHighlight(['<Redacted>'])
      .build()
    ]
  }

  void 'triggers no rule'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Harmless']))

    when:
    dataListener.onDataAvailable(flow, ctx, db, false)

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    flow.blocking == false
  }

  void 'powerwaf exceptions do not propagate'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES, [get: { null }] as List)

    when:
    dataListener.onDataAvailable(flow, ctx, db, false)

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    assert !flow.blocking
  }

  void 'timeout is honored'() {
    injectSysConfig('appsec.waf.timeout', '1')
    PowerWAFModule.createLimitsObject()
    setupWithStubConfigService()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v' + ('a' * 4000)]))
    ChangeableFlow flow = new ChangeableFlow()

    when:
    dataListener.onDataAvailable(flow, ctx, db, false)

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive() }
    assert !flow.blocking

    cleanup:
    injectSysConfig('appsec.waf.timeout', ConfigDefaults.DEFAULT_APPSEC_WAF_TIMEOUT as String)
    PowerWAFModule.createLimitsObject()
  }

  void 'configuration can be given later'() {
    def cfgService = new StubAppSecConfigService([waf: null])
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    cfgService.init()
    pwafModule.config(cfgService)

    then:
    thrown AppSecModule.AppSecModuleActivationException

    when:
    cfgService.listeners['waf'].onNewSubconfig(defaultConfig['waf'], reconf)
    dataListener = pwafModule.dataSubscriptions.first()
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * reconf.reloadSubscriptions()
  }

  void 'rule data given through configuration'() {
    setupWithStubConfigService()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()
    ChangeableFlow flow = Mock()
    def ipData = [
      [
        id  : 'ip_data',
        type: 'ip_with_expiration',
        data: [[
            value     : '1.2.3.4',
            expiration: '0',
          ]]
      ]
    ]

    when:
    service.currentAppSecConfig.with {
      mergedAsmData.addConfig('my_config', ipData)
      it.dirtyStatus.data = true
      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener = pwafModule.dataSubscriptions.first()
    def bundle = MapDataBundle.of(KnownAddresses.REQUEST_INFERRED_CLIENT_IP, '1.2.3.4')
    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then:
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateAdditive(_, true) >> { pwafAdditive = it[0].openAdditive() }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * flow.setAction({ it.blocking })
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    0 * _
  }

  private static List toggleById(String id, boolean enabled) {
    [
      [
        rules_target: [[
            rule_id: id
          ]],
        enabled: enabled
      ]
    ]
  }

  void 'reloading rules clears waf data and rule toggling'() {
    setupWithStubConfigService()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()
    ChangeableFlow flow = Mock()
    def ipData = [
      [
        id  : 'ip_data',
        type: 'ip_with_expiration',
        data: [[
            value     : '1.2.3.4',
            expiration: '0',
          ]]
      ]
    ]

    when: 'reconfigure with data and toggling'
    service.currentAppSecConfig.with {
      mergedAsmData.addConfig('my_config', ipData)
      it.dirtyStatus.data = true
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('my_config', toggleById('ip_match_rule', false), [], [], []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener = pwafModule.dataSubscriptions.first()
    def bundle = MapDataBundle.of(KnownAddresses.REQUEST_INFERRED_CLIENT_IP, '1.2.3.4')
    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then: 'no match; rule is disabled'
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive() >> { pwafAdditive.close() }
    0 * _

    when: 'removing data and override config'
    service.currentAppSecConfig.with {
      mergedAsmData.removeConfig('my_config')
      it.dirtyStatus.data = true
      def dirtyStatus = userConfigs.removeConfig('my_config')
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then: 'no match; data was cleared (though rule is no longer disabled)'
    1 * ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive() >> {pwafAdditive.close()}
    1 * reconf.reloadSubscriptions()
    0 * _

    when: 'data is readded'
    service.currentAppSecConfig.with {
      mergedAsmData.addConfig('my_config', ipData)
      it.dirtyStatus.data = true
      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then: 'now we have match'
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * flow.setAction({ it.blocking })
    1 * ctx.closeAdditive() >> {pwafAdditive.close()}
    1 * flow.isBlocking()
    0 * _

    when: 'toggling the rule off'
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('my_config', toggleById('ip_match_rule', false), [], [], []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener.onDataAvailable(flow, ctx, bundle, false)
    ctx.closeAdditive()

    then: 'nothing again; we disabled the rule'
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateAdditive(_, true) >> { pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    0 * _
  }

  void 'rule toggling data given through configuration'() {
    setupWithStubConfigService()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()
    ChangeableFlow flow = Mock()

    when: 'rule disabled in config b'
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('b', toggleById('ua0-600-12x', false), [], [], []))

      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    dataListener = pwafModule.dataSubscriptions.first()
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    1 * reconf.reloadSubscriptions()
    // no attack
    1 * ctx.getOrCreateAdditive(_, true) >> { pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive() >> {pwafAdditive.close()}
    0 * _

    when: 'rule enabled in config a has no effect'
    // later configurations have precedence (b > a)
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('a', toggleById('ua0-600-12x', true), [], [], []))

      it.dirtyStatus.mergeFrom(dirtyStatus)
      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    1 * reconf.reloadSubscriptions()
    // no attack
    1 * ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive() >> {pwafAdditive.close()}
    0 * _

    when: 'rule enabled in config c overrides b'
    // later configurations have precedence (c > a)
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('c', toggleById('ua0-600-12x', true), [], [], []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    1 * reconf.reloadSubscriptions()
    // attack found
    1 * ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * flow.isBlocking()
    1 * flow.setAction({ it.blocking })
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.closeAdditive() >> {pwafAdditive.close()}
    0 * _

    when: 'removing c restores the state before c was added (rule disabled)'
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.removeConfig('c')
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    ctx.closeAdditive()

    then:
    1 * reconf.reloadSubscriptions()
    // no attack
    1 * ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    0 * _
  }

  void 'initial configuration has unknown addresses'() {
    Address<String> doesNotExistAddress = new Address<>("server.request.headers.does-not-exist")
    def cfgService = new StubAppSecConfigService(waf:
    new CurrentAppSecConfig(
    ddConfig: AppSecConfig.valueOf([
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
    ])))

    when:
    cfgService.init()
    pwafModule.config(cfgService)

    then:
    !pwafModule.dataSubscriptions.first().subscribedAddresses.contains(doesNotExistAddress)
  }

  void 'bad initial configuration is given results in no subscriptions'() {
    def cfgService = new StubAppSecConfigService([waf: [:]])

    when:
    cfgService.init()
    pwafModule.config(cfgService)

    then:
    thrown AppSecModule.AppSecModuleActivationException
    pwafModule.dataSubscriptions.empty
  }

  void 'rule data not a config'() {
    def confService = new StubAppSecConfigService(waf: [])

    when:
    confService.init()
    pwafModule.config(confService)

    then:
    thrown AppSecModule.AppSecModuleActivationException

    then:
    pwafModule.ctxAndAddresses.get() == null
  }

  void 'bad ResultWithData - empty list'() {
    def waf = new PowerWAFModule()
    Powerwaf.ResultWithData rwd = new Powerwaf.ResultWithData(null, "[]", null, null)
    Collection ret

    when:
    ret = waf.buildEvents(rwd)

    then:
    ret.isEmpty()
  }

  void 'bad ResultWithData - empty object'() {
    def waf = new PowerWAFModule()
    Powerwaf.ResultWithData rwd = new Powerwaf.ResultWithData(null, "[{}]", null, null)
    Collection ret

    when:
    ret = waf.buildEvents(rwd)

    then:
    ret.isEmpty()
  }

  /**
   * This test simulates double REQUEST_END with increasing interval
   * The race condition shouldn't happen when closing Additive
   */
  @Unroll("test repeated #n time")
  void 'parallel REQUEST_END should not cause race condition'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()
    AppSecRequestContext ctx = new AppSecRequestContext()

    when:
    for (int t = 0; t < 20; t++) {
      CountDownLatch latch = new CountDownLatch(1)
      dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
      Thread thread = new Thread({ p ->
        latch.countDown()
        ctx.closeAdditive()
      })
      thread.start()
      latch.await()
      sleep(t)
      ctx.close()
      thread.join()
    }

    then:
    // java.lang.IllegalStateException: This Additive is no longer online
    // Should not be thrown
    noExceptionThrown()

    where:
    n << (1..3)
  }

  private Map<String, Object> getDefaultConfig() {
    def service = new StubAppSecConfigService()
    service.init()
    service.lastConfig
  }
}
