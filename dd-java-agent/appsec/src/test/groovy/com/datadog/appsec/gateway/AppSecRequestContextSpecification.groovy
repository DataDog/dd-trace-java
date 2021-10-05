package com.datadog.appsec.gateway

import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.event.data.StringKVPair
import com.datadog.appsec.report.raw.events.attack.Attack010
import datadog.trace.test.util.DDSpecification

import java.time.Instant

class AppSecRequestContextSpecification extends DDSpecification {

  void 'implements DataBundle'() {
    DataBundle ctx = new AppSecRequestContext()

    when:
    ctx.addAll(MapDataBundle.of(KnownAddresses.REQUEST_URI_RAW, '/a'))

    then:
    ctx.size() == 1
    ctx.get(KnownAddresses.REQUEST_URI_RAW) == '/a'
    ctx.allAddresses as List == [KnownAddresses.REQUEST_URI_RAW]
    ctx.hasAddress(KnownAddresses.REQUEST_URI_RAW)

    when:
    def iter = ctx.iterator()

    then:
    iter.hasNext()

    when:
    def elem = iter.next()

    then:
    elem.key == KnownAddresses.REQUEST_URI_RAW
    elem.value == '/a'
  }

  void 'it is closeable'() {
    def ctx = new AppSecRequestContext()

    expect:
    assert ctx.respondsTo('close')

    when:
    ctx.close()

    then:
    notThrown(Exception)
  }

  void 'adding headers after they are said to be finished is forbidden'() {
    AppSecRequestContext ctx = new AppSecRequestContext()

    when:
    ctx.finishHeaders()

    and:
    ctx.addHeader('a', 'b')

    then:
    ctx.finishedHeaders == true
    thrown(IllegalStateException)

    when:
    ctx.addCookie(new StringKVPair('a', 'b'))

    then:
    thrown(IllegalStateException)
  }

  void 'adding uri a second time is forbidden'() {
    AppSecRequestContext ctx = new AppSecRequestContext()

    when:
    ctx.rawURI = '/a'
    ctx.rawURI = '/b'

    then:
    thrown(IllegalStateException)
    ctx.savedRawURI == '/a'
  }

  void 'saves cookies and other headers'() {
    AppSecRequestContext ctx = new AppSecRequestContext()

    when:
    ctx.addCookie(new StringKVPair('a', 'c'))
    ctx.addHeader('user-agent', 'foo')

    then:
    ctx.collectedHeaders['user-agent'] == ['foo']
    ctx.collectedCookies == [['a', 'c']]
  }

  void 'can save the URI'() {
    AppSecRequestContext ctx = new AppSecRequestContext()

    when:
    ctx.savedRawURI = '/a'

    then:
    ctx.savedRawURI == '/a'
  }

  void 'can collect attacks'() {
    AppSecRequestContext ctx = new AppSecRequestContext()
    def now = Instant.now()

    when:
    ctx.reportAttack(new Attack010(detectedAt: now))
    ctx.reportAttack(new Attack010())
    def attacks = ctx.transferCollectedAttacks()

    then:
    attacks.size() == 2
    attacks[0].detectedAt.is(now)
    attacks[1] != null

    when:
    ctx.reportAttack(new Attack010())

    then:
    thrown IllegalStateException
  }

  void 'collect attacks when none reported'() {
    when:
    AppSecRequestContext ctx = new AppSecRequestContext()

    then:
    ctx.transferCollectedAttacks().empty
  }
}
