package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DD64bTraceId
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.DynamicConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP
import static datadog.trace.core.propagation.W3CHttpCodec.OT_BAGGAGE_PREFIX
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_PARENT_KEY
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_STATE_KEY

class W3CHttpExtractorTest extends DDSpecification {

  private static final String TEST_TP_DROP = "00-00000000000000000000000000000001-123456789abcdef0-00"
  private static final String TEST_TP_KEEP = "00-00000000000000000000000000000001-123456789abcdef0-01"
  private static final long TEST_SPAN_ID = 1311768467463790320L
  private static final DDTraceId TRACE_ID_ONE = DDTraceId.fromHex("00000000000000000000000000000001")
  private static final DDTraceId TRACE_ID_NO_HIGH_LOW_MAX = DDTraceId.fromHex("0000000000000000ffffffffffffffff")
  private static final DDTraceId TRACE_ID_LOW_MAX = DDTraceId.fromHex("123456789abcdef0ffffffffffffffff")

  private DynamicConfig dynamicConfig
  private HttpCodec.Extractor _extractor

  private HttpCodec.Extractor getExtractor() {
    _extractor ?: (_extractor = W3CHttpCodec.newExtractor(Config.get(), { dynamicConfig.captureTraceConfig() }))
  }

  boolean origAppSecActive

  void setup() {
    dynamicConfig = DynamicConfig.create()
      .setHeaderTags(["SOME_HEADER": "some-tag"])
      .setBaggageMapping(["SOME_CUSTOM_BAGGAGE_HEADER": "some-baggage", "SOME_CUSTOM_BAGGAGE_HEADER_2": "some-CaseSensitive-baggage"])
      .apply()
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE
    ActiveSubsystems.APPSEC_ACTIVE = true

    injectSysConfig(PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, "true")
  }

  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive
  }

  def "extract traceparent '#traceparent'"() {
    setup:
    HashMap<String, String> headers = []
    if (traceparent) {
      headers.put(W3CHttpCodec.TRACE_PARENT_KEY, traceparent)
    }

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    if (tpValid) {
      assert context.traceId == traceId
      assert context.spanId == spanId
      assert context.samplingPriority == priority
    } else {
      assert context == null
    }

    where:
    traceparent                                                 | tpValid | traceId           | spanId       | priority
    null                                                        | false   | null              | null         | null
    '00-00000000000000000000000000000000-123456789abcdef0-01'   | false   | null              | null         | null
    '00-123456789abcdef00000000000000000-123456789abcdef0-01'   | false   | null              | null         | null
    '00-00000000000000000000000000000001-0000000000000000-01'   | false   | null              | null         | null
    '00-00000000000000000000000000000001-123456789abcdef0-01'   | true    | TRACE_ID_ONE      | TEST_SPAN_ID | SAMPLER_KEEP
    '\t00-00000000000000000000000000000001-123456789abcdef0-01' | true    | TRACE_ID_ONE      | TEST_SPAN_ID | SAMPLER_KEEP
    '00-00000000000000000000000000000001-123456789abcdef0-01\t' | true    | TRACE_ID_ONE      | TEST_SPAN_ID | SAMPLER_KEEP
    ' 00-00000000000000000000000000000001-123456789abcdef0-01 ' | true    | TRACE_ID_ONE      | TEST_SPAN_ID | SAMPLER_KEEP
    '00-0000000000000000ffffffffffffffff-ffffffffffffffff-01'   | true    | TRACE_ID_NO_HIGH_LOW_MAX | DDSpanId.MAX | SAMPLER_KEEP
    '00-0000000000000000ffffffffffffffff-ffffffffffffffff-00'   | true    | TRACE_ID_NO_HIGH_LOW_MAX | DDSpanId.MAX | SAMPLER_DROP
    '00-123456789abcdef0ffffffffffffffff-123456789abcdef0-00'   | true    | TRACE_ID_LOW_MAX  | TEST_SPAN_ID | SAMPLER_DROP
    '00-123456789abcdef0ffffffffffffffFf-123456789abcdef0-00'   | false   | null              | null         | null
    '00-123456789abcdeF0ffffffffffffffff-123456789abcdef0-00'   | false   | null              | null         | null
    '00-123456789abcdef0fffffffffFffffff-123456789abcdef0-00'   | false   | null              | null         | null
    '00-123456789abcdef0ffffffffffffffff-123456789Abcdef0-00'   | false   | null              | null         | null
    '00-123456789äbcdef0ffffffffffffffff-123456789abcdef0-00'   | false   | null              | null         | null
    '00-123456789abcdef0ffffffffäfffffff-123456789abcdef0-00'   | false   | null              | null         | null
    '00-123456789abcdef0ffffffffffffffff-123456789äbcdef0-00'   | false   | null              | null         | null
    '01-00000000000000000000000000000001-0000000000000001-02'   | true    | TRACE_ID_ONE      | 1            | SAMPLER_DROP
    '000-0000000000000000000000000000001-0000000000000001-01'   | false   | null              | null         | null
    '00-0000000000000000000000000000001 -0000000000000001-01'   | false   | null              | null         | null
    '00-0000000000000000000000000000001-0000000000000001-01'    | false   | null              | null         | null
    '00-00000000000000000000000000000001-000000000000001-01'    | false   | null              | null         | null
    '00-00000000000000000000000000000001-0000000000000001-0'    | false   | null              | null         | null
    'ff-00000000000000000000000000000001-0000000000000001-00'   | false   | null              | null         | null
    'fe-00000000000000000000000000000001-0000000000000001-02'   | true    | TRACE_ID_ONE      | 1            | SAMPLER_DROP
    '00-00000000000000000000000000000001-0000000000000001-03-0' | false   | null              | null         | null
    'fe-00000000000000000000000000000001-0000000000000001-02.0' | false   | null              | null         | null
  }

  def "check max from W3C trace ids"() {
    expect:
    traceId.toLong() == DD64bTraceId.MAX.toLong()

    where:
    traceId << [TRACE_ID_LOW_MAX, TRACE_ID_NO_HIGH_LOW_MAX]
  }

  def "extract traceparent, tracestate, and http headers (#traceparent #tracestate)"() {
    setup:
    def headers = [
      ""                                      : 'empty key',
      (TRACE_PARENT_KEY.toUpperCase())        : traceparent,
      (TRACE_STATE_KEY.toUpperCase())         : tracestate,
      (OT_BAGGAGE_PREFIX.toUpperCase() + 'k1'): 'v1',
      (OT_BAGGAGE_PREFIX.toUpperCase() + 'k2'): 'v2',
      SOME_HEADER                             : 'my-interesting-info',
      SOME_CUSTOM_BAGGAGE_HEADER              : 'my-interesting-baggage-info',
      SOME_CUSTOM_BAGGAGE_HEADER_2            : 'my-interesting-baggage-info-2',
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == TRACE_ID_ONE
    context.spanId == TEST_SPAN_ID
    context.baggage == ['k1'                        : 'v1',
      'k2'                        : 'v2',
      'some-baggage'              : 'my-interesting-baggage-info',
      'some-CaseSensitive-baggage': 'my-interesting-baggage-info-2']
    context.tags == ['some-tag': 'my-interesting-info']
    context.samplingPriority == priority
    if (decisionMaker != null) {
      assert context.propagationTags.createTagMap() == ['_dd.p.dm': "-$decisionMaker"]
    } else {
      assert context.propagationTags.createTagMap() == [:]
    }
    if (origin) {
      assert context.origin.toString() == origin
    }

    where:
    traceparent  | tracestate               | priority     | decisionMaker             | origin
    TEST_TP_KEEP | ''                       | SAMPLER_KEEP | SamplingMechanism.DEFAULT | null
    TEST_TP_DROP | ''                       | SAMPLER_DROP | null                      | null
    TEST_TP_KEEP | "dd=s:2;o:some"          | USER_KEEP    | null                      | 'some'
    TEST_TP_KEEP | "dd=s:2;o:some;t.dm:-4"  | USER_KEEP    | SamplingMechanism.MANUAL  | 'some'
    TEST_TP_DROP | "dd=s:2;o:some;t.dm:-4"  | SAMPLER_DROP | null                      | 'some'
    TEST_TP_DROP | "dd=s:-1;o:some"         | USER_DROP    | null                      | 'some'
    TEST_TP_DROP | "dd=s:-1;o:some;t.dm:-4" | USER_DROP    | SamplingMechanism.MANUAL  | 'some'
    TEST_TP_KEEP | "dd=s:-1;o:some;t.dm:-4" | SAMPLER_KEEP | SamplingMechanism.DEFAULT | 'some'
  }

  def "extract header tags with no propagation"() {
    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    !(context instanceof ExtractedContext)
    context.getTags() == ['some-tag': 'my-interesting-info']

    where:
    headers << [[SOME_HEADER: 'my-interesting-info'],]
  }

  def "extract headers with forwarding"() {
    when:
    TagContext context = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    context != null
    !(context instanceof ExtractedContext)
    context.forwarded == "for=$forwardedIp:$forwardedPort"

    when:
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap())

    then:
    context instanceof ExtractedContext
    context.traceId.toLong() == 1
    context.spanId.toLong() == 2
    context.forwarded == "for=$forwardedIp:$forwardedPort"

    where:
    forwardedIp = "1.2.3.4"
    forwardedPort = "1234"
    tagOnlyCtx = [
      'Forwarded' : "for=$forwardedIp:$forwardedPort"
    ]
    fullCtx = [
      (TRACE_PARENT_KEY.toUpperCase())        : '00-00000000000000000000000000000001-0000000000000002-01',
      'Forwarded' : "for=$forwardedIp:$forwardedPort"
    ]
  }

  def "extract headers with x-forwarding"() {
    setup:
    String forwardedIp = '1.2.3.4'
    String forwardedPort = '1234'
    def tagOnlyCtx = [
      'X-Forwarded-For' : forwardedIp,
      'X-Forwarded-Port': forwardedPort
    ]
    def fullCtx = [
      (TRACE_PARENT_KEY.toUpperCase())        : '00-00000000000000000000000000000001-0000000000000002-01',
      'x-forwarded-for'           : forwardedIp,
      'x-forwarded-port'          : forwardedPort
    ]

    when:
    TagContext context = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    context != null
    context instanceof TagContext
    context.XForwardedFor == forwardedIp
    context.XForwardedPort == forwardedPort

    when:
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap())

    then:
    context instanceof ExtractedContext
    context.traceId.toLong() == 1
    context.spanId.toLong() == 2
    context.XForwardedFor == forwardedIp
    context.XForwardedPort == forwardedPort
  }

  def "extract empty headers returns null"() {
    expect:
    extractor.extract(["ignored-header": "ignored-value"], ContextVisitors.stringValuesMap()) == null
  }

  void 'extract headers with ip resolution disabled'() {
    setup:
    injectSysConfig(TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED, 'false')

    def tagOnlyCtx = [
      'X-Forwarded-For': '::1',
      'User-agent': 'foo/bar',
    ]

    when:
    TagContext ctx = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    ctx != null
    ctx.XForwardedFor == null
    ctx.userAgent == 'foo/bar'
  }


  void 'extract headers with ip resolution disabled — appsec disabled variant'() {
    setup:
    ActiveSubsystems.APPSEC_ACTIVE = false

    def tagOnlyCtx = [
      'X-Forwarded-For': '::1',
      'User-agent': 'foo/bar',
    ]

    when:
    TagContext ctx = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    ctx != null
    ctx.XForwardedFor == null
  }

  void 'custom IP header collection does not disable standard ip header collection'() {
    setup:
    injectSysConfig(TracerConfig.TRACE_CLIENT_IP_HEADER, "my-header")

    def tagOnlyCtx = [
      'X-Forwarded-For': '::1',
      'My-Header': '8.8.8.8',
    ]

    when:
    def ctx = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    ctx != null
    ctx.XForwardedFor == '::1'
    ctx.customIpHeader == '8.8.8.8'
  }

  def "extract http headers with end to end #endToEndStartTime"() {
    setup:
    def headers = [
      ''                                      : 'empty key',
      (TRACE_PARENT_KEY.toUpperCase())        : '00-00000000000000000000000000000001-123456789abcdef0-01',
      (OT_BAGGAGE_PREFIX.toUpperCase() + 'k1'): 'v1',
      (OT_BAGGAGE_PREFIX.toUpperCase() + 't0'): endToEndStartTime,
      (OT_BAGGAGE_PREFIX.toUpperCase() + 'k2'): 'v2',
      SOME_HEADER                             : 'my-interesting-info',
      SOME_CUSTOM_BAGGAGE_HEADER              : 'my-interesting-baggage-info',
      SOME_CUSTOM_BAGGAGE_HEADER_2            : 'my-interesting-baggage-info-2',
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == TRACE_ID_ONE
    context.spanId == TEST_SPAN_ID
    context.baggage == ['k1': 'v1',
      'k2': 'v2',
      'some-baggage': 'my-interesting-baggage-info',
      'some-CaseSensitive-baggage': 'my-interesting-baggage-info-2']
    context.tags == ['some-tag': 'my-interesting-info']
    context.endToEndStartTime == endToEndStartTime * 1000000L

    where:
    endToEndStartTime << [0, 1610001234]
  }

  def "baggage is mapped on context creation"() {
    setup:
    def headers = [
      (TRACE_PARENT_KEY)                      : traceparent,
      SOME_CUSTOM_BAGGAGE_HEADER              : 'mappedBaggageValue',
      (OT_BAGGAGE_PREFIX.toUpperCase() + 'k1'): 'v1',
      (OT_BAGGAGE_PREFIX.toUpperCase() + 'k2'): 'v2',
      SOME_ARBITRARY_HEADER                   : 'my-interesting-info',
    ]

    when:
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    assert context != null
    if (tpValid) {
      assert context.getTraceId() == TRACE_ID_ONE
      assert context.getSpanId()  == 1
    }
    context.getBaggage() == [
      'some-baggage'     : 'mappedBaggageValue',
      'k1'               : 'v1',
      'k2'               : 'v2',
    ]

    where:
    tpValid | traceparent
    false   | '00-00000000000000000000000000000000-123456789abcdef0-01'
    false   | '00-00000000000000000000000000000001-0000000000000000-01'
    true    | '00-00000000000000000000000000000001-0000000000000001-01'
  }

  def "extract common http headers"() {
    setup:
    def headers = [
      (HttpCodec.USER_AGENT_KEY): 'some-user-agent',
      (HttpCodec.X_CLUSTER_CLIENT_IP_KEY): '1.1.1.1',
      (HttpCodec.X_REAL_IP_KEY): '2.2.2.2',
      (HttpCodec.X_CLIENT_IP_KEY): '3.3.3.3',
      (HttpCodec.TRUE_CLIENT_IP_KEY): '4.4.4.4',
      (HttpCodec.FORWARDED_FOR_KEY): '5.5.5.5',
      (HttpCodec.FORWARDED_KEY): '6.6.6.6',
      (HttpCodec.FASTLY_CLIENT_IP_KEY): '7.7.7.7',
      (HttpCodec.CF_CONNECTING_IP_KEY): '8.8.8.8',
      (HttpCodec.CF_CONNECTING_IP_V6_KEY): '9.9.9.9',
    ]

    when:
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    assert context.userAgent == 'some-user-agent'
    assert context.XClusterClientIp == '1.1.1.1'
    assert context.XRealIp == '2.2.2.2'
    assert context.XClientIp == '3.3.3.3'
    assert context.trueClientIp == '4.4.4.4'
    assert context.forwardedFor == '5.5.5.5'
    assert context.forwarded == '6.6.6.6'
    assert context.fastlyClientIp == '7.7.7.7'
    assert context.cfConnectingIp == '8.8.8.8'
    assert context.cfConnectingIpv6 == '9.9.9.9'
  }

  def "mark inconsistent tid as propagation error"() {
    setup:
    def headers = [
      (TRACE_PARENT_KEY.toUpperCase())        : traceparent,
      (TRACE_STATE_KEY.toUpperCase())         : tracestate,
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.getPropagationTags().createTagMap() == expectedTags

    where:
    traceparent                                               | tracestate                  | consitent
    '00-123456789abcdef00fedcba987654321-123456789abcdef0-01' | ''                          | true
    '00-123456789abcdef00fedcba987654321-123456789abcdef0-01' | "dd=t.tid:123456789abcdef0" | true
    '00-123456789abcdef00fedcba987654321-123456789abcdef0-01' | "dd=t.tid:123456789abcdef1" | false
    tid = tracestate.empty ? '' : tracestate.substring(9)
    defaultTags = ['_dd.p.dm': '-0', '_dd.p.tid': '123456789abcdef0']
    expectedTags = consitent ? defaultTags : defaultTags + ['_dd.propagation_error': "inconsistent_tid $tid"]
  }
}
