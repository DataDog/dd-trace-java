package com.datadog.appsec.powerwaf

import com.datadog.appsec.AppSecModule
import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.DataListener
import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.report.raw.events.AppSecEvent100
import com.datadog.appsec.report.raw.events.Parameter100
import com.datadog.appsec.test.StubAppSecConfigService
import datadog.trace.test.util.DDSpecification
import io.sqreen.powerwaf.Powerwaf

class PowerWAFModuleSpecification extends DDSpecification {
  private static final DataBundle ATTACK_BUNDLE = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
  new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v0']))

  AppSecRequestContext ctx = Mock()

  PowerWAFModule pwafModule = new PowerWAFModule()
  DataListener listener

  private void setupWithStubConfigService() {
    def service = new StubAppSecConfigService()
    service.init(false)
    pwafModule.config(service)
    listener = pwafModule.dataSubscriptions.first()
  }

  void 'is named powerwaf'() {
    expect:
    pwafModule.name == 'powerwaf'
  }

  void 'triggers a rule through the user agent header'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()

    when:
    listener.onDataAvailable(flow, ctx, ATTACK_BUNDLE)

    then:
    flow.blocking == true
  }

  void 'reports events'() {
    setupWithStubConfigService()
    AppSecEvent100 event

    when:
    listener.onDataAvailable(Mock(ChangeableFlow), ctx, ATTACK_BUNDLE)

    then:
    ctx.reportEvents(_ as Collection<AppSecEvent100>, _) >> { event = it[0].iterator().next() }

    event.rule.id == 'ua0-600-12x'
    event.rule.name == 'Arachni'
    event.rule.tags == ['type': 'security_scanner', 'category': 'attack_attempt']

    event.ruleMatch.highlight == ['Arachni/v']
    event.ruleMatch.operator == 'match_regex'
    event.ruleMatch.operatorValue == '^Arachni\\/v'
    event.ruleMatch.parameters == [
      new Parameter100.Parameter100Builder()
      .withAddress('server.request.headers.no_cookies')
      .withKeyPath(['user-agent'])
      .withValue('Arachni/v0')
      .build()
    ]
  }

  void 'triggers no rule'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Harmless']))

    when:
    listener.onDataAvailable(flow, ctx, db)

    then:
    flow.blocking == false
  }

  void 'powerwaf exceptions do not propagate'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES, [get: { null }] as List)

    when:
    listener.onDataAvailable(flow, ctx, db)

    then:
    assert !flow.blocking
  }

  void 'subscribes no events'() {
    expect:
    pwafModule.eventSubscriptions.empty == true
  }

  void 'configuration can be given later'() {
    def cfgService = new StubAppSecConfigService([waf: null])

    when:
    cfgService.init(false)
    pwafModule.config(cfgService)

    then:
    thrown AppSecModule.AppSecModuleActivationException

    when:
    listener = pwafModule.dataSubscriptions.first()
    cfgService.listeners['waf'].onNewSubconfig(defaultConfig['waf'])
    listener.onDataAvailable(Mock(ChangeableFlow), ctx, ATTACK_BUNDLE)

    then:
    1 * ctx.reportEvents(_ as Collection<AppSecEvent100>, _)
  }

  void 'bad initial configuration is given results in no attacks detected'() {
    def cfgService = new StubAppSecConfigService([waf: [:]])

    when:
    cfgService.init(false)
    pwafModule.config(cfgService)

    then:
    thrown AppSecModule.AppSecModuleActivationException

    when:
    listener = pwafModule.dataSubscriptions.first()
    listener.onDataAvailable(Mock(ChangeableFlow), ctx, ATTACK_BUNDLE)

    then:
    0 * ctx._
  }

  void 'rule data not a config'() {
    def confService = new StubAppSecConfigService(waf: [])

    when:
    confService.init(false)
    pwafModule.config(confService)

    then:
    thrown AppSecModule.AppSecModuleActivationException

    then:
    pwafModule.ctx.get() == null
  }

  void 'bad ActionWithData - empty list'() {
    def waf = new PowerWAFModule()
    Powerwaf.ActionWithData actionWithData = new Powerwaf.ActionWithData(null, "[]")
    Optional ret

    when:
    ret = waf.buildEvent(actionWithData)

    then:
    !ret.isPresent()
  }

  void 'bad ActionWithData - empty object'() {
    def waf = new PowerWAFModule()
    Powerwaf.ActionWithData actionWithData = new Powerwaf.ActionWithData(null, "[{}]")
    Optional ret

    when:
    ret = waf.buildEvent(actionWithData)

    then:
    !ret.isPresent()
  }

  private Map<String, Object> getDefaultConfig() {
    def service = new StubAppSecConfigService()
    service.init(false)
    service.lastConfig
  }
}
