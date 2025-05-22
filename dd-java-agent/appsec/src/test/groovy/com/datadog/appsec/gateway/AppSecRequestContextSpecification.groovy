package com.datadog.appsec.gateway


import com.datadog.appsec.config.CurrentAppSecConfig
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.report.AppSecEvent
import datadog.trace.api.telemetry.LogCollector
import datadog.trace.test.logging.TestLogCollector
import datadog.trace.util.stacktrace.StackTraceEvent
import com.datadog.appsec.test.StubAppSecConfigService
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.stacktrace.StackTraceFrame
import com.datadog.ddwaf.WafContext
import com.datadog.ddwaf.Waf
import com.datadog.ddwaf.WafHandle

class AppSecRequestContextSpecification extends DDSpecification {

  AppSecRequestContext ctx = new AppSecRequestContext()

  void 'implements DataBundle'() {
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
    assert ctx.respondsTo('close')

    when:
    ctx.close()

    then:
    notThrown(Exception)
  }

  void 'adding headers after they are said to be finished is forbidden'() {
    when:
    ctx.finishRequestHeaders()

    and:
    ctx.addRequestHeader('a', 'b')

    then:
    ctx.finishedRequestHeaders
    thrown(IllegalStateException)

    when:
    ctx.addCookies(a: ['b'])

    then:
    thrown(IllegalStateException)
  }

  void 'adding uri a second time is forbidden'() {
    when:
    ctx.rawURI = '/a'
    ctx.rawURI = '/b'

    then:
    thrown(IllegalStateException)
    ctx.savedRawURI == '/a'
  }

  void 'saves cookies and other headers'() {
    when:
    ctx.addCookies([a: ['c']])
    ctx.addRequestHeader('user-agent', 'foo')

    then:
    ctx.requestHeaders['user-agent'] == ['foo']
    ctx.cookies == [a: ['c']]
  }

  void 'can save the URI'() {
    when:
    ctx.savedRawURI = '/a'

    then:
    ctx.savedRawURI == '/a'
  }

  void 'can collect events'() {
    when:
    ctx.reportEvents([new AppSecEvent(), new AppSecEvent()])
    def events = ctx.transferCollectedEvents()

    then:
    events.size() == 2
    events[0] != null
    events[1] != null

    when:
    ctx.reportEvents([new AppSecEvent()])
    events = ctx.transferCollectedEvents()

    then:
    events.size() == 1
    events[0] != null
  }

  void 'collect events when none reported'() {
    expect:
    ctx.transferCollectedEvents().empty
  }

  void 'can collect stack traces'() {
    setup:
    StackTraceElement element = new StackTraceElement('class', 'method', 'file', 1)
    StackTraceFrame frame = new StackTraceFrame(1, element)
    StackTraceEvent event = new StackTraceEvent([frame], 'java', 'id', 'message')

    when:
    ctx.reportStackTrace(event)
    final result = ctx.getStackTraces()

    then:
    result.size() == 1
    result[0].id == 'id'
    result[0].message == 'message'
    result[0].language == 'java'
    result[0].frames.size() == 1
    result[0].frames[0].id == 1
    result[0].frames[0].text == 'class.method(file:1)'
    result[0].frames[0].file == 'file'
    result[0].frames[0].line == 1
    result[0].frames[0].class_name == 'class'
    result[0].frames[0].function == 'method'
  }

  void 'collect stack traces when none reported'() {
    expect:
    ctx.getStackTraces() == null
  }

  void 'headers allow list should contains only lowercase names'() {
    expect:
    headers.each {
      assert it == it.toLowerCase(): "REASON: Allow header name \"$it\" MUST be lowercase"
    }

    where:
    headers                                                 | name
    AppSecRequestContext.DEFAULT_REQUEST_HEADERS_ALLOW_LIST | 'Default request headers'
    AppSecRequestContext.REQUEST_HEADERS_ALLOW_LIST         | 'Request headers'
    AppSecRequestContext.RESPONSE_HEADERS_ALLOW_LIST        | 'Response headers'
  }

  void 'basic headers collection test'() {
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
    when:
    ctx.addRequestHeader(null, 'value')
    ctx.addRequestHeader('key', null)

    then:
    ctx.requestHeaders.isEmpty()
  }

  void 'concat multiple values for same header'() {
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

  private WafContext createWafContext() {
    Waf.initialize false
    def service = new StubAppSecConfigService()
    service.init()
    CurrentAppSecConfig config = service.lastConfig['waf']
    String uniqueId = UUID.randomUUID() as String
    config.dirtyStatus.markAllDirty()
    WafHandle context = Waf.createHandle(uniqueId, config.mergedUpdateConfig.rawConfig)
    new WafContext(context)
  }

  void 'close closes the wafContext'() {
    setup:
    def wafContext = createWafContext()

    when:
    ctx.wafContext = wafContext
    ctx.close()

    then:
    ctx.wafContext == null
    !wafContext.online
  }

  void 'test isThrottled'(){
    setup:
    def rateLimiter = Mock(RateLimiter)
    def appSecRequestContext = new AppSecRequestContext()

    when: 'rate limiter is called and throttled is set'
    def result = appSecRequestContext.isThrottled(rateLimiter)

    then:
    1 * rateLimiter.isThrottled() >> true
    assert result

    when: 'rate limiter is not called more than once per appsec context returns first result'
    def result2 = appSecRequestContext.isThrottled(rateLimiter)

    then:
    0 * rateLimiter.isThrottled()
    result == result2
  }


  void 'test that internal data is cleared on close'() {
    setup:
    final ctx = new AppSecRequestContext()

    when:
    ctx.requestHeaders.put('Accept', ['*'])
    ctx.responseHeaders.put('Content-Type', ['text/plain'])
    ctx.collectedCookies = [cookie : ['test']]
    ctx.persistentData.put(KnownAddresses.REQUEST_METHOD, 'GET')
    ctx.derivatives = ['a': 'b']
    ctx.wafContext = createWafContext()
    ctx.close()

    then:
    ctx.wafContext == null
    ctx.derivatives == null

    ctx.requestHeaders.isEmpty()
    ctx.responseHeaders.isEmpty()
    ctx.cookies.isEmpty()
    ctx.persistentData.isEmpty()
  }

  def "test increase and get WafTimeouts"() {
    when:
    ctx.increaseWafTimeouts()
    ctx.increaseWafTimeouts()

    then:
    ctx.getWafTimeouts() == 2
  }

  def "test increase and get RaspTimeouts"() {
    when:
    ctx.increaseRaspTimeouts()
    ctx.increaseRaspTimeouts()

    then:
    ctx.getRaspTimeouts() == 2
  }

  void 'close logs if request end was not called'() {
    given:
    TestLogCollector.enable()
    def ctx = new AppSecRequestContext()

    when:
    ctx.close()

    then:
    def log = TestLogCollector.drainCapturedLogs().find { it.message.contains('Request end event was not called before close') }
    log != null
    log.marker == LogCollector.SEND_TELEMETRY

    cleanup:
    TestLogCollector.disable()
  }
}
