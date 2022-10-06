package com.datadog.appsec.powerwaf

import com.datadog.appsec.AppSecModule
import com.datadog.appsec.config.AppSecConfig
import com.datadog.appsec.config.AppSecModuleConfigurer
import com.datadog.appsec.config.TraceSegmentPostProcessor
import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.DataListener
import com.datadog.appsec.event.EventListener
import com.datadog.appsec.event.EventType
import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.report.raw.events.AppSecEvent100
import com.datadog.appsec.report.raw.events.Parameter
import com.datadog.appsec.report.raw.events.Tags
import com.datadog.appsec.test.StubAppSecConfigService
import datadog.trace.api.TraceSegment
import datadog.trace.api.gateway.Flow
import datadog.trace.test.util.DDSpecification
import io.sqreen.powerwaf.Powerwaf
import io.sqreen.powerwaf.PowerwafContext
import io.sqreen.powerwaf.PowerwafMetrics
import spock.lang.Unroll

import java.util.concurrent.CountDownLatch

import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP

class PowerWAFModuleSpecification extends DDSpecification {
  private static final DataBundle ATTACK_BUNDLE = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
  new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v0']))

  AppSecRequestContext ctx = Mock()

  StubAppSecConfigService service
  PowerWAFModule pwafModule = new PowerWAFModule()
  DataListener dataListener
  EventListener eventListener

  def pwafAdditive
  PowerwafMetrics metrics

  private void setupWithStubConfigService() {
    service = new StubAppSecConfigService()
    service.init()
    pwafModule.config(service)
    dataListener = pwafModule.dataSubscriptions.first()
    eventListener = pwafModule.eventSubscriptions.first()
  }

  void 'use default actions if no defined in config'() {
    when:
    PowerWAFModule powerWAFModule = new PowerWAFModule()
    StubAppSecConfigService confService = new StubAppSecConfigService("no_actions_config.json")
    confService.init()
    powerWAFModule.config(confService)

    then:
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.size() == 1
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('block') != null
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('block').parameters == [
      status_code: 403,
      type:'auto',
      grpc_status_code: 10
    ]
  }

  void 'override default actions by config'() {
    when:
    PowerWAFModule powerWAFModule = new PowerWAFModule()
    StubAppSecConfigService confService = new StubAppSecConfigService("override_actions_config.json")
    confService.init()
    powerWAFModule.config(confService)

    then:
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.size() == 1
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('block') != null
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('block').parameters == [
      status_code: 500,
      type:'html',
    ]
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
    eventListener.onEvent(ctx, EventType.REQUEST_END)

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> {
      PowerwafContext pwCtx = it[0] as PowerwafContext
      pwafAdditive = pwCtx.openAdditive()
      metrics = pwCtx.createMetrics()
      pwafAdditive
    }
    1 * ctx.getWafMetrics() >> metrics
    1 * ctx.closeAdditive()
    1 * ctx.reportEvents(_, _)
    0 * ctx._(*_)
    flow.blocking == true
    flow.action.statusCode == 418
    flow.action.blockingContentType == Flow.Action.BlockingContentType.HTML
  }

  void 'no metrics are set if waf metrics are off'() {
    setup:
    injectSysConfig('appsec.waf.metrics', 'false')
    pwafModule = new PowerWAFModule() // replace the one created too soon
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    eventListener.onEvent(ctx, EventType.REQUEST_END)

    then:
    1 * ctx.getOrCreateAdditive(_, false) >> { it[0].openAdditive() }
    1 * ctx.getWafMetrics() >> null
    1 * ctx.closeAdditive()
    1 * ctx.reportEvents(_, _)
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
    eventListener.onEvent(ctx, EventType.REQUEST_END)
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
    AppSecEvent100 event

    when:
    dataListener.onDataAvailable(Mock(ChangeableFlow), ctx, ATTACK_BUNDLE, false)
    eventListener.onEvent(ctx, EventType.REQUEST_END)

    then:
    ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    ctx.reportEvents(_ as Collection<AppSecEvent100>, _) >> { event = it[0].iterator().next() }

    event.rule.id == 'ua0-600-12x'
    event.rule.name == 'Arachni'
    event.rule.tags == new Tags.TagsBuilder()
      .withType('security_scanner')
      .withCategory('attack_attempt')
      .build()

    event.ruleMatches[0].operator == 'match_regex'
    event.ruleMatches[0].operatorValue == '^Arachni\\/v'
    event.ruleMatches[0].parameters == [
      new Parameter.ParameterBuilder()
      .withAddress('server.request.headers.no_cookies')
      .withKeyPath(['user-agent'])
      .withValue('Arachni/v0')
      .withHighlight(['Arachni/v'])
      .build()
    ]
  }

  void 'redaction with default settings'() {
    setupWithStubConfigService()
    AppSecEvent100 event

    when:
    def bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': [password: 'Arachni/v0']]))
    dataListener.onDataAvailable(Mock(ChangeableFlow), ctx, bundle, false)
    eventListener.onEvent(ctx, EventType.REQUEST_END)

    then:
    ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    ctx.reportEvents(_ as Collection<AppSecEvent100>, _) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters == [
      new Parameter.ParameterBuilder()
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
    AppSecEvent100 event

    when:
    def bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': [password: 'Arachni/v0']]))
    dataListener.onDataAvailable(Mock(ChangeableFlow), ctx, bundle, false)
    eventListener.onEvent(ctx, EventType.REQUEST_END)

    then:
    ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    ctx.reportEvents(_ as Collection<AppSecEvent100>, _) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters == [
      new Parameter.ParameterBuilder()
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
    AppSecEvent100 event

    when:
    dataListener.onDataAvailable(Mock(ChangeableFlow), ctx, ATTACK_BUNDLE, false)
    eventListener.onEvent(ctx, EventType.REQUEST_END)

    then:
    ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    ctx.reportEvents(_ as Collection<AppSecEvent100>, _) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters == [
      new Parameter.ParameterBuilder()
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
    ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    flow.blocking == false
  }

  void 'powerwaf exceptions do not propagate'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES, [get: { null }] as List)

    when:
    dataListener.onDataAvailable(flow, ctx, db, false)

    then:
    ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    assert !flow.blocking
  }

  void 'subscribes 1 event'() {
    expect:
    pwafModule.eventSubscriptions.isEmpty() == false
    pwafModule.eventSubscriptions.first().eventType == EventType.REQUEST_END
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
    eventListener = pwafModule.eventSubscriptions.first()
    dataListener.onDataAvailable(Mock(ChangeableFlow), ctx, ATTACK_BUNDLE, false)
    eventListener.onEvent(ctx, EventType.REQUEST_END)

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent100>, _)
    1 * reconf.reloadSubscriptions()
  }

  void 'rule data given through configuration'() {
    setupWithStubConfigService()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()
    ChangeableFlow flow = Mock()

    when:
    service.listeners['waf_data'].onNewSubconfig([
      [
        id: 'ip_data',
        type: 'ip_with_expiration',
        data: [[
            value: '1.2.3.4',
            expiration: '0',
          ]]
      ]
    ],
    reconf
    )
    dataListener = pwafModule.dataSubscriptions.first()
    eventListener = pwafModule.eventSubscriptions.first()
    def bundle = MapDataBundle.of(KnownAddresses.REQUEST_INFERRED_CLIENT_IP, '1.2.3.4')
    dataListener.onDataAvailable(flow, ctx, bundle, false)
    eventListener.onEvent(ctx, EventType.REQUEST_END)

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent100>, _)
    1 * ctx.getWafMetrics()
    1 * flow.setAction({ it.blocking })
    1 * ctx.closeAdditive()
    0 * _
  }

  void 'rule toggling data given through configuration'() {
    setupWithStubConfigService()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()
    ChangeableFlow flow = Mock()

    when:
    service.listeners['waf_rules_override'].onNewSubconfig([
      'ua0-600-12x': false
    ],
    reconf
    )
    dataListener = pwafModule.dataSubscriptions.first()
    eventListener = pwafModule.eventSubscriptions.first()
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    eventListener.onEvent(ctx, EventType.REQUEST_END)

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.closeAdditive()
    0 * _

    when:
    service.listeners['waf_rules_override'].onNewSubconfig([:], reconf )
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, false)
    eventListener.onEvent(ctx, EventType.REQUEST_END)

    then:
    1 * ctx.getOrCreateAdditive(_, true) >> { it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * flow.setAction({ it.blocking })
    1 * ctx.reportEvents(_ as Collection<AppSecEvent100>, _)
    1 * ctx.closeAdditive()
    0 * _
  }

  void 'initial configuration has unknown addresses'() {
    def cfgService = new StubAppSecConfigService(waf: AppSecConfig.valueOf([
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
                    address: 'server.request.headers.does-not-exist',
                    key_path: ['user-agent']]
                ],
                regex: '^Arachni\\/v'
              ],
              operator: 'match_regex'
            ]
          ],
        ]
      ]
    ]))

    when:
    cfgService.init()
    pwafModule.config(cfgService)

    then:
    pwafModule.dataSubscriptions.first().subscribedAddresses.empty
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
    Powerwaf.ResultWithData rwd = new Powerwaf.ResultWithData(null, "[]")
    Collection ret

    when:
    ret = waf.buildEvents(rwd, [:])

    then:
    ret.isEmpty()
  }

  void 'bad ResultWithData - empty object'() {
    def waf = new PowerWAFModule()
    Powerwaf.ResultWithData rwd = new Powerwaf.ResultWithData(null, "[{}]")
    Collection ret

    when:
    ret = waf.buildEvents(rwd, [:])

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
        eventListener.onEvent(ctx, EventType.REQUEST_END)
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
