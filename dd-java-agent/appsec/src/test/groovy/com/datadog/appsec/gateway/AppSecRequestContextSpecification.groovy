package com.datadog.appsec.gateway

import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.event.data.StringKVPair
import spock.lang.Specification

class AppSecRequestContextSpecification extends Specification {

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
    expect:
    assert new AppSecRequestContext().respondsTo('close')
  }

  void 'adding headers and uri after time is forbidden'() {
    AppSecRequestContext ctx = new AppSecRequestContext()

    when:
    ctx.rawURI = '/b'
    ctx.finishHeaders()
    ctx.savedRawURI

    then:
    thrown(IllegalStateException)

    when:
    ctx.addHeader('a', 'b')
    ctx.collectedHeaders

    then:
    thrown(IllegalStateException)

    when:
    ctx.collectedCookies

    then:
    thrown(IllegalStateException)
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
}
