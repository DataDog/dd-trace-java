package com.datadog.appsec.powerwaf

import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.DataListener
import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.gateway.AppSecRequestContext
import spock.lang.Specification

class PowerWAFModuleSpecification extends Specification {
  AppSecRequestContext ctx = Mock()

  PowerWAFModule pwafModule = new PowerWAFModule()

  DataListener listener = pwafModule.dataSubscriptions.first()

  void 'is named powerwaf'() {
    expect:
    pwafModule.name == 'powerwaf'
  }

  void 'triggers a rule through the user agent header'() {
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v']))

    when:
    listener.onDataAvailable(flow, ctx, db)

    then:
    flow.blocking == true
  }

  void 'triggers no rule'() {
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Harmless']))

    when:
    listener.onDataAvailable(flow, ctx, db)

    then:
    flow.blocking == false
  }

  void 'powerwaf exceptions do not propagate'() {
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES, [get: { null }] as List)

    when:
    listener.onDataAvailable(flow, ctx, db)

    then:
    assert !flow.blocking
  }
}
