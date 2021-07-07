package com.datadog.appsec.powerwaf

import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.DataListener
import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.report.raw.events.attack.Attack010
import com.datadog.appsec.report.raw.events.attack._definitions.rule_match.Parameter
import com.datadog.appsec.test.StubAppSecConfigService
import spock.lang.Specification

class PowerWAFModuleSpecification extends Specification {
  private static final DataBundle ATTACK_BUNDLE = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
  new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v0']))

  AppSecRequestContext ctx = Mock()

  PowerWAFModule pwafModule = new PowerWAFModule()
  DataListener listener

  private void setupWithStubConfigService() {
    pwafModule.config(new StubAppSecConfigService())
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

  void 'reports attacks'() {
    setupWithStubConfigService()
    Attack010 attack

    when:
    listener.onDataAvailable(Mock(ChangeableFlow), ctx, ATTACK_BUNDLE)

    then:
    ctx.reportAttack(_ as Attack010) >> { attack = it[0] }
    attack.blocked == Boolean.TRUE
    attack.type == 'waf'

    attack.rule.id == 'rule_ua_60012'
    attack.rule.name == 'security_scanner-blocking'
    attack.rule.set == 'waf'

    attack.ruleMatch.highlight == ['Arachni/v']
    attack.ruleMatch.operator == '@rx'
    attack.ruleMatch.operatorValue == '^Arachni\\/v'
    attack.ruleMatch.parameters == [
      new Parameter.ParameterBuilder()
      .withName('server.request.headers.no_cookies')
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
    pwafModule.config(cfgService)
    listener = pwafModule.dataSubscriptions.first()
    cfgService.listeners['waf'].onNewSubconfig(new StubAppSecConfigService().lastConfig['waf'])
    listener.onDataAvailable(Mock(ChangeableFlow), ctx, ATTACK_BUNDLE)

    then:
    1 * ctx.reportAttack(_ as Attack010)
  }

  void 'bad initial configuration is given results in no attacks detected'() {
    def cfgService = new StubAppSecConfigService([waf: [:]])

    when:
    pwafModule.config(cfgService)
    listener = pwafModule.dataSubscriptions.first()
    listener.onDataAvailable(Mock(ChangeableFlow), ctx, ATTACK_BUNDLE)

    then:
    0 * ctx._
  }

  void 'rule data not a map'() {
    def confService = new StubAppSecConfigService(waf: [])

    when:
    pwafModule.config(confService)

    then:
    pwafModule.ctx.get() == null
  }
}
