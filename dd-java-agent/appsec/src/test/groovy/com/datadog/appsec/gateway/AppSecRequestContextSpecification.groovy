package com.datadog.appsec.gateway

import com.datadog.appsec.ddwaf.WafInitialization
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.report.AppSecEvent
import com.datadog.ddwaf.Waf
import com.datadog.ddwaf.WafBuilder
import com.datadog.ddwaf.WafContext
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.trace.api.Config
import datadog.trace.api.telemetry.LogCollector
import datadog.trace.test.logging.TestLogCollector
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.stacktrace.StackTraceEvent
import datadog.trace.util.stacktrace.StackTraceFrame
import okio.Okio

class AppSecRequestContextSpecification extends DDSpecification {

  private static final JsonAdapter<Map<String, Object>> ADAPTER =
  new Moshi.Builder()
  .build()
  .adapter(Types.newParameterizedType(Map, String, Object))

  AppSecRequestContext ctx = new AppSecRequestContext()
  WafBuilder wafBuilder

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
    WafInitialization.ONLINE
    Waf.initialize false
    wafBuilder?.close()
    wafBuilder = new WafBuilder()
    def stream = getClass().classLoader.getResourceAsStream("test_multi_config.json")
    wafBuilder.addOrUpdateConfig("test", ADAPTER.fromJson(Okio.buffer(Okio.source(stream))))
    new WafContext(wafBuilder.buildWafHandleInstance())
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
    // Use reportDerivatives to properly set values via AtomicReference
    ctx.reportDerivatives(['test_attr': ['value': 'test_value']])
    ctx.wafContext = createWafContext()
    ctx.close()

    then:
    ctx.wafContext == null
    // Check that derivatives AtomicReference contains null after close
    ctx.derivatives.get() == null

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
  void 'test that processed attributes are cleared on close'() {
    setup:
    def derivatives = [
      'numeric': '42',
      'string': 'value'
    ]

    when:
    ctx.reportDerivatives(derivatives)
    ctx.close()

    then:
    ctx.getDerivativeKeys().isEmpty()
  }

  def "test attribute handling with literal values and request data extraction"() {
    given:
    def context = new AppSecRequestContext()
    context.setMethod("POST")
    context.setScheme("https")
    context.setRawURI("/api/test")
    context.setRoute("/api/{param}")
    context.setResponseStatus(200)
    context.addRequestHeader("user-agent", "TestAgent/1.0")
    context.addRequestHeader("content-type", "application/json")

    // Test data for attributes
    def attributes = [
      "_dd.appsec.s.res.headers": [
        "value": "literal-header-value"
      ],
      "_dd.appsec.s.res.method": [
        "address": "server.request.method"
      ],
      "_dd.appsec.s.res.scheme": [
        "address": "server.request.scheme"
      ],
      "_dd.appsec.s.res.uri": [
        "address": "server.request.uri.raw"
      ],
      "_dd.appsec.s.res.route": [
        "address": "server.request.route"
      ],
      "_dd.appsec.s.res.status": [
        "address": "server.response.status"
      ],
      "_dd.appsec.s.res.user_agent": [
        "address": "server.request.headers",
        "key_path": ["user-agent"]
      ],
      "_dd.appsec.s.res.content_type": [
        "address": "server.request.headers",
        "key_path": ["content-type"]
      ],
      "_dd.appsec.s.res.user_agent_lower": [
        "address": "server.request.headers",
        "key_path": ["user-agent"],
        "transformers": ["lowercase"]
      ],
      "_dd.appsec.s.res.content_type_upper": [
        "address": "server.request.headers",
        "key_path": ["content-type"],
        "transformers": ["uppercase"]
      ]
    ]

    when:
    context.reportDerivatives(attributes)
    def keys = context.getDerivativeKeys()

    then:
    keys.size() == 10
    keys.contains("_dd.appsec.s.res.headers")
    keys.contains("_dd.appsec.s.res.method")
    keys.contains("_dd.appsec.s.res.scheme")
    keys.contains("_dd.appsec.s.res.uri")
    keys.contains("_dd.appsec.s.res.route")
    keys.contains("_dd.appsec.s.res.status")
    keys.contains("_dd.appsec.s.res.user_agent")
    keys.contains("_dd.appsec.s.res.content_type")
    keys.contains("_dd.appsec.s.res.user_agent_lower")
    keys.contains("_dd.appsec.s.res.content_type_upper")
  }

  def "test attribute handling with unknown address"() {
    given:
    def context = new AppSecRequestContext()

    def attributes = [
      "_dd.appsec.s.res.unknown": [
        "address": "server.request.unknown"
      ]
    ]

    when:
    context.reportDerivatives(attributes)
    def keys = context.getDerivativeKeys()

    then:
    keys.size() == 0 // No attributes should be added for unknown addresses
  }

  def "test attribute handling with invalid key path"() {
    given:
    def context = new AppSecRequestContext()
    context.addRequestHeader("user-agent", "TestAgent/1.0")

    def attributes = [
      "_dd.appsec.s.res.invalid": [
        "address": "server.request.headers",
        "key_path": ["non-existent-header"]
      ]
    ]

    when:
    context.reportDerivatives(attributes)
    def keys = context.getDerivativeKeys()

    then:
    keys.size() == 0 // No attributes should be added for invalid key paths
  }

  void 'test sampling of requests'() {
    given:
    final maxRequests = Config.get().apiSecurityMaxDownstreamRequestBodyAnalysis
    final context = new AppSecRequestContext()
    final random = new Random()
    final requestIds = (0..maxRequests).collect { random.nextLong() }

    when:
    final map = requestIds.collectEntries{ [(it) : context.sampleHttpClientRequest(it)] }

    then:
    map.values().count { it } == maxRequests
    map.each { requestId, sampled ->
      assert context.isHttpClientRequestSampled(requestId) == sampled
    }
  }

  def "test commitDerivatives with different value types"() {
    given:
    def context = new AppSecRequestContext()
    def mockTraceSegment = Mock(datadog.trace.api.internal.TraceSegment)
    def committedTags = [:]

    // Mock setTagTop to capture what's being set
    mockTraceSegment.setTagTop(_ as String, _ as Object) >> { String key, Object value ->
      committedTags[key] = value
    }

    // Set up derivatives with different types
    def derivatives = [
      'numeric_int': ['value': 42],
      'numeric_double': ['value': 3.14],
      'string_value': ['value': 'test_string'],
      'boolean_true': ['value': true],
      'boolean_false': ['value': false],
      'numeric_string': ['value': '100'],
      'double_string': ['value': '99.5'],
      'non_numeric_string': ['value': 'not_a_number']
    ]

    when:
    context.reportDerivatives(derivatives)
    def result = context.commitDerivatives(mockTraceSegment)

    then:
    result == true
    committedTags.size() == 8

    // Verify numeric values are preserved as numbers
    committedTags['numeric_int'] == 42
    committedTags['numeric_int'] instanceof Integer

    // In Groovy, 3.14 is BigDecimal, not Double - both are Numbers
    committedTags['numeric_double'] == 3.14
    committedTags['numeric_double'] instanceof Number

    // Verify strings are handled correctly
    committedTags['string_value'] == 'test_string'
    committedTags['string_value'] instanceof String

    // Verify booleans are preserved
    committedTags['boolean_true'] == true
    committedTags['boolean_true'] instanceof Boolean

    committedTags['boolean_false'] == false
    committedTags['boolean_false'] instanceof Boolean

    // Verify numeric strings are converted to numbers
    committedTags['numeric_string'] == 100
    committedTags['numeric_string'] instanceof Long

    committedTags['double_string'] == 99.5
    committedTags['double_string'] instanceof Number

    // Verify non-numeric strings remain strings
    committedTags['non_numeric_string'] == 'not_a_number'
    committedTags['non_numeric_string'] instanceof String
  }

  def "test commitDerivatives clears derivatives atomically"() {
    given:
    def context = new AppSecRequestContext()
    def mockTraceSegment = Mock(datadog.trace.api.internal.TraceSegment)

    def derivatives = [
      'attr1': ['value': 'value1'],
      'attr2': ['value': 'value2']
    ]

    when:
    context.reportDerivatives(derivatives)

    then:
    context.getDerivativeKeys().size() == 2

    when:
    context.commitDerivatives(mockTraceSegment)

    then:
    // Derivatives should be cleared after commit
    context.getDerivativeKeys().isEmpty()
    context.derivatives.get() == null
  }

  def "test commitDerivatives with null TraceSegment returns false"() {
    given:
    def context = new AppSecRequestContext()

    when:
    def result = context.commitDerivatives(null)

    then:
    result == false
  }

  def "test multiple reportDerivatives calls accumulate values"() {
    given:
    def context = new AppSecRequestContext()

    when:
    // First report
    context.reportDerivatives([
      'attr1': ['value': 'value1'],
      'attr2': ['value': 'value2']
    ])

    then:
    context.getDerivativeKeys().size() == 2
    context.getDerivativeKeys().contains('attr1')
    context.getDerivativeKeys().contains('attr2')

    when:
    // Second report - should accumulate
    context.reportDerivatives([
      'attr3': ['value': 'value3'],
      'attr4': ['value': 'value4']
    ])

    then:
    context.getDerivativeKeys().size() == 4
    context.getDerivativeKeys().contains('attr1')
    context.getDerivativeKeys().contains('attr2')
    context.getDerivativeKeys().contains('attr3')
    context.getDerivativeKeys().contains('attr4')
  }

  def "test multiple reportDerivatives with overlapping keys uses last value"() {
    given:
    def context = new AppSecRequestContext()
    def mockTraceSegment = Mock(datadog.trace.api.internal.TraceSegment)
    def committedTags = [:]

    mockTraceSegment.setTagTop(_ as String, _ as Object) >> { String key, Object value ->
      committedTags[key] = value
    }

    when:
    // First report
    context.reportDerivatives([
      'attr1': ['value': 'first_value']
    ])

    // Second report with same key - should overwrite
    context.reportDerivatives([
      'attr1': ['value': 'second_value']
    ])

    context.commitDerivatives(mockTraceSegment)

    then:
    committedTags['attr1'] == 'second_value'
  }

  def "test reportDerivatives maintains data integrity under concurrent access"() {
    given:
    def context = new AppSecRequestContext()
    def numThreads = 3
    def startLatch = new java.util.concurrent.CountDownLatch(1)

    when:
    // Simulate concurrent updates from multiple threads
    def executorService = java.util.concurrent.Executors.newFixedThreadPool(numThreads)
    def futures = []

    futures << executorService.submit({
      startLatch.await() // Wait for all threads to be ready
      context.reportDerivatives(['attr1': ['value': 'value1']])
      context.reportDerivatives(['attr2': ['value': 'value2']])
    })

    futures << executorService.submit({
      startLatch.await() // Wait for all threads to be ready
      context.reportDerivatives(['attr3': ['value': 'value3']])
      context.reportDerivatives(['attr4': ['value': 'value4']])
    })

    futures << executorService.submit({
      startLatch.await() // Wait for all threads to be ready
      context.reportDerivatives(['attr5': ['value': 'value5']])
      context.reportDerivatives(['attr6': ['value': 'value6']])
    })

    // Release all threads at once to maximize concurrent execution
    startLatch.countDown()

    // Wait for all tasks to complete using the futures
    futures.each { it.get() }
    executorService.shutdown()

    then:
    // Verify all attributes were added despite concurrent access
    def keys = context.getDerivativeKeys()
    keys.size() >= 6  // At least the 6 we explicitly added
    ['attr1', 'attr2', 'attr3', 'attr4', 'attr5', 'attr6'].each { attr ->
      assert keys.contains(attr), "Missing attribute: ${attr}"
    }
  }

  def "test numeric conversion with edge cases: #description"() {
    given:
    def context = new AppSecRequestContext()
    def mockTraceSegment = Mock(datadog.trace.api.internal.TraceSegment)
    def committedTags = [:]

    mockTraceSegment.setTagTop(_ as String, _ as Object) >> { String key, Object value ->
      committedTags[key] = value
    }

    when:
    context.reportDerivatives(['test_attr': ['value': inputValue]])
    context.commitDerivatives(mockTraceSegment)

    then:
    // When conversion fails (expectedValue is null), the original string value should be preserved
    // When conversion succeeds, the converted numeric value should be used
    committedTags['test_attr'] == (expectedValue != null ? expectedValue : inputValue)

    where:
    description                          | inputValue                     | expectedValue
    // Valid integers
    'zero'                               | '0'                            | 0L
    'positive integer'                   | '42'                           | 42L
    'negative integer'                   | '-100'                         | -100L
    'integer with plus sign'             | '+999'                         | 999L
    'large valid long'                   | '9223372036854775807'          | 9223372036854775807L
    'large negative long'                | '-9223372036854775808'         | -9223372036854775808L

    // Valid decimals
    'simple decimal'                     | '3.14'                         | 3.14d
    'negative decimal'                   | '-0.5'                         | -0.5d
    'decimal with plus sign'             | '+99.99'                       | 99.99d
    'zero decimal'                       | '0.0'                          | 0.0d
    'decimal with many digits'           | '123.456789'                   | 123.456789d

    // Whitespace handling (should now parse after trim - issue #10494 fix)
    'leading whitespace integer'         | ' 42'                          | 42L
    'trailing whitespace integer'        | '42 '                          | 42L
    'both whitespace integer'            | ' 42 '                         | 42L
    'tab and newline whitespace'         | '\t100\n'                      | 100L
    'multiple spaces decimal'            | '  -3.14  '                    | -3.14d

    // Empty and null
    'null value'                         | null                           | null
    'empty string'                       | ''                             | null
    'whitespace only'                    | '   '                          | null
    'tab only'                           | '\t'                           | null

    // Invalid formats (should return null, original string preserved)
    'alphabetic string'                  | 'abc'                          | null
    'alphanumeric string'                | '12x34'                        | null
    'multiple decimals'                  | '3.14.15'                      | null
    'multiple signs'                     | '+-5'                          | null
    'sign in middle'                     | '12-34'                        | null

    // Sign-only strings
    'plus sign only'                     | '+'                            | null
    'minus sign only'                    | '-'                            | null
    'plus with whitespace'               | ' + '                          | null

    // Overflow cases (should return null gracefully)
    'long overflow positive'             | '9223372036854775808'          | null
    'long overflow negative'             | '-9223372036854775809'         | null
    'very large number'                  | '99999999999999999999999'      | null

    // Scientific notation (now supported for backward compatibility)
    'scientific notation lowercase'      | '1e10'                         | 1.0e10d
    'scientific notation uppercase'      | '1E10'                         | 1.0E10d
    'scientific with decimal'            | '1.5e10'                       | 1.5e10d
    'scientific negative exponent'       | '3.5E-7'                       | 3.5E-7d
    'scientific with sign'               | '-2.5e+3'                      | -2.5e+3d
    'scientific integer base'            | '5e3'                          | 5000.0d

    // Exotic number formats (not supported)
    'hexadecimal'                        | '0x10'                         | null
    'binary'                             | '0b1010'                       | null
    'octal'                              | '0777'                         | 777L

    // Edge decimal cases (note: .5 and 5. are valid Java double literals)
    'decimal starts with dot'            | '.5'                           | 0.5d
    'decimal ends with dot'              | '5.'                           | 5.0d
    'dot only'                           | '.'                            | null
    'multiple dots'                      | '...'                          | null

    // Regression - ensure existing valid formats still work
    'regression valid integer'           | '100'                          | 100L
    'regression valid decimal'           | '99.5'                         | 99.5d
    'regression negative'                | '-50'                          | -50L

    // Special characters
    'comma separator'                    | '1,000'                        | null
    'underscore separator'               | '1_000'                        | null
    'currency symbol'                    | '$100'                         | null
    'percentage'                         | '50%'                          | null

    // Edge cases with zeros
    'zero with plus'                     | '+0'                           | 0L
    'zero with minus'                    | '-0'                           | 0L
    'decimal zero variations'            | '0.00'                         | 0.0d
  }

  def "test getDerivativeKeys with empty derivatives"() {
    given:
    def context = new AppSecRequestContext()

    when:
    def keys = context.getDerivativeKeys()

    then:
    keys.isEmpty()
  }

  def "test reportDerivatives with extracted values from request data"() {
    given:
    def context = new AppSecRequestContext()
    context.setMethod("POST")
    context.addRequestHeader("x-custom-header", "custom_value")

    def derivatives = [
      'extracted_method': [
        'address': 'server.request.method'
      ],
      'extracted_header': [
        'address': 'server.request.headers',
        'key_path': ['x-custom-header']
      ]
    ]

    when:
    context.reportDerivatives(derivatives)
    def keys = context.getDerivativeKeys()

    then:
    keys.size() == 2
    keys.contains('extracted_method')
    keys.contains('extracted_header')
  }

  def "test reportDerivatives with transformers"() {
    given:
    def context = new AppSecRequestContext()
    context.addRequestHeader("user-agent", "Mozilla/5.0")

    def derivatives = [
      'ua_lowercase': [
        'address': 'server.request.headers',
        'key_path': ['user-agent'],
        'transformers': ['lowercase']
      ],
      'ua_uppercase': [
        'address': 'server.request.headers',
        'key_path': ['user-agent'],
        'transformers': ['uppercase']
      ]
    ]

    when:
    context.reportDerivatives(derivatives)
    def keys = context.getDerivativeKeys()

    then:
    keys.size() == 2
    keys.contains('ua_lowercase')
    keys.contains('ua_uppercase')
  }
}
