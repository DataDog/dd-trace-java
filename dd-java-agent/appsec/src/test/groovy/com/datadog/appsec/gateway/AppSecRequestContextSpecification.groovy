package com.datadog.appsec.gateway

import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.event.data.StringKVPair
import com.datadog.appsec.report.raw.events.AppSecEvent100
import datadog.trace.test.util.DDSpecification

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
    ctx.addRequestHeader('a', 'b')

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
    ctx.addRequestHeader('user-agent', 'foo')

    then:
    ctx.requestHeaders['user-agent'] == ['foo']
    ctx.collectedCookies == [['a', 'c']]
  }

  void 'can save the URI'() {
    AppSecRequestContext ctx = new AppSecRequestContext()

    when:
    ctx.savedRawURI = '/a'

    then:
    ctx.savedRawURI == '/a'
  }

  void 'can collect events'() {
    AppSecRequestContext ctx = new AppSecRequestContext()

    when:
    ctx.reportEvents([new AppSecEvent100(), new AppSecEvent100()], null)
    def events = ctx.transferCollectedEvents()

    then:
    events.size() == 2
    events[0] != null
    events[1] != null

    when:
    ctx.reportEvents([new AppSecEvent100()], null)

    then:
    thrown IllegalStateException
  }

  void 'collect events when none reported'() {
    when:
    AppSecRequestContext ctx = new AppSecRequestContext()

    then:
    ctx.transferCollectedEvents().empty
  }

  void 'headers allow list should contains only lowercase names'() {
    expect:
    AppSecRequestContext.HEADERS_ALLOW_LIST.each {
      assert it == it.toLowerCase() : "REASON: Allow header name \"$it\" MUST be lowercase"
    }
  }

  void 'basic headers collection test'() {
    given:
    def ctx = new AppSecRequestContext()

    when:
    ctx.addRequestHeader('Host', '127.0.0.1')
    ctx.addRequestHeader('Content-Type', 'text/html; charset=UTF-8')
    ctx.addRequestHeader('Custom-Header', 'value1')
    ctx.addRequestHeader('Accept', 'application/json')

    then:
    ctx.requestHeaders == [
      'host': ['127.0.0.1'],
      'content-type': ['text/html; charset=UTF-8'],
      'custom-header': ['value1'],
      'accept': ['application/json']] as Map
  }

  void 'null headers should be ignored'() {
    given:
    def ctx = new AppSecRequestContext()

    when:
    ctx.addRequestHeader(null, 'value')
    ctx.addRequestHeader('key', null)

    then:
    ctx.requestHeaders.isEmpty()
  }

  void 'concat multiple values for same header'() {
    given:
    def ctx = new AppSecRequestContext()

    when:
    ctx.addRequestHeader('Custom-Header', 'value1')
    ctx.addRequestHeader('CUSTOM-HEADER', 'value2')
    ctx.addRequestHeader('Accept', 'application/json')
    ctx.addRequestHeader('accept', 'application/xml')

    then:
    ctx.requestHeaders == [
      'custom-header': ['value1', 'value2'],
      'accept': ['application/json', 'application/xml']] as Map
  }
}
