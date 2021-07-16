package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DDId
import datadog.trace.api.Function
import datadog.trace.api.function.Supplier
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.InstrumentationGateway
import datadog.trace.api.gateway.RequestContext
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.api.PropagationStyle.B3
import static datadog.trace.api.PropagationStyle.DATADOG
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX

class HttpExtractorTest extends DDSpecification {

  @Shared
  String outOfRangeTraceId = (TRACE_ID_MAX + 1).toString()

  def "extract http headers"() {
    setup:
    Config config = Mock(Config) {
      getPropagationStylesToExtract() >> styles
    }
    HttpCodec.Extractor extractor = HttpCodec.createExtractor(config, ["SOME_HEADER": "some-tag"], null)

    final Map<String, String> actual = [:]
    if (datadogTraceId != null) {
      actual.put(DatadogHttpCodec.TRACE_ID_KEY.toUpperCase(), datadogTraceId)
    }
    if (datadogSpanId != null) {
      actual.put(DatadogHttpCodec.SPAN_ID_KEY.toUpperCase(), datadogSpanId)
    }
    if (b3TraceId != null) {
      actual.put(B3HttpCodec.TRACE_ID_KEY.toUpperCase(), b3TraceId)
    }
    if (b3SpanId != null) {
      actual.put(B3HttpCodec.SPAN_ID_KEY.toUpperCase(), b3SpanId)
    }

    if (putDatadogFields) {
      actual.put("SOME_HEADER", "my-interesting-info")
    }

    when:
    final TagContext context = extractor.extract(actual, ContextVisitors.stringValuesMap())

    then:
    if (tagContext) {
      assert context instanceof TagContext
    } else {
      if (expectedTraceId == null) {
        assert context == null
      } else {
        assert context.traceId == DDId.from(expectedTraceId)
        assert context.spanId == DDId.from(expectedSpanId)
      }
    }

    if (expectDatadogFields) {
      if (tagContext) {
        assert context.tags == ["b3.traceid": b3TraceId, "b3.spanid": b3SpanId, "some-tag": "my-interesting-info"]
      } else {
        assert context.tags == ["some-tag": "my-interesting-info"]
      }
    }

    where:
    // spotless:off
    styles        | datadogTraceId    | datadogSpanId     | b3TraceId         | b3SpanId          | expectedTraceId | expectedSpanId | putDatadogFields | expectDatadogFields | tagContext
    [DATADOG, B3] | "1"               | "2"               | "a"               | "b"               | "1"             | "2"            | true             | true                | false
    [DATADOG, B3] | null              | null              | "a"               | "b"               | "10"            | "11"           | false            | false               | true
    [DATADOG, B3] | null              | null              | "a"               | "b"               | null            | null           | true             | true                | true
    [DATADOG]     | "1"               | "2"               | "a"               | "b"               | "1"             | "2"            | true             | true                | false
    [B3]          | "1"               | "2"               | "a"               | "b"               | "10"            | "11"           | false            | false               | false
    [B3, DATADOG] | "1"               | "2"               | "a"               | "b"               | "10"            | "11"           | false            | false               | false
    []            | "1"               | "2"               | "a"               | "b"               | null            | null           | false            | false               | false
    [DATADOG, B3] | "abc"             | "2"               | "a"               | "b"               | "10"            | "11"           | false            | false               | false
    [DATADOG]     | "abc"             | "2"               | "a"               | "b"               | null            | null           | false            | false               | false
    [DATADOG, B3] | outOfRangeTraceId | "2"               | "a"               | "b"               | "10"            | "11"           | false            | false               | false
    [DATADOG, B3] | "1"               | outOfRangeTraceId | "a"               | "b"               | "10"            | "11"           | false            | false               | false
    [DATADOG]     | outOfRangeTraceId | "2"               | "a"               | "b"               | null            | null           | false            | false               | false
    [DATADOG]     | "1"               | outOfRangeTraceId | "a"               | "b"               | null            | null           | false            | false               | false
    [DATADOG, B3] | "1"               | "2"               | outOfRangeTraceId | "b"               | "1"             | "2"            | true             | false               | false
    [DATADOG, B3] | "1"               | "2"               | "a"               | outOfRangeTraceId | "1"             | "2"            | true             | false               | false
    // spotless:on
  }

  def "send headers to instrumentation gateway"() {
    setup:
    def ig = new InstrumentationGateway()
    def callbacks = new IGCallBacks(reqContext)
    if (reqStarted) {
      ig.registerCallback(Events.REQUEST_STARTED, callbacks)
    }
    if (reqHeader) {
      ig.registerCallback(Events.REQUEST_HEADER, callbacks)
    }
    if (reqHeaderDone) {
      ig.registerCallback(Events.REQUEST_HEADER_DONE, callbacks)
    }
    Map<String, String> headers = ["foo": "bar", "some": "thing", "another": "value"]
    Config config = Mock(Config) {
      getPropagationStylesToExtract() >> []
    }
    HttpCodec.Extractor extractor = HttpCodec.createExtractor(config, Collections.emptyMap(), ig)

    when:
    extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    reqStartedCount == callbacks.reqStartedCount
    reqHeaderCount == callbacks.headers.size()
    reqHeaderDoneCount == callbacks.reqHeaderDoneCount
    reqHeaderCount == 0 ? true : headers == callbacks.headers

    where:
    // spotless:off
    reqStarted | reqContext             | reqHeader | reqHeaderDone | reqStartedCount | reqHeaderCount | reqHeaderDoneCount
    false      | null                   | false     | false         | 0               | 0              | 0
    false      | new RequestContext(){} | false     | false         | 0               | 0              | 0
    true       | null                   | false     | false         | 1               | 0              | 0
    true       | new RequestContext(){} | false     | false         | 1               | 0              | 0
    true       | new RequestContext(){} | true      | false         | 1               | 3              | 0
    true       | new RequestContext(){} | true      | true          | 1               | 3              | 1
    // spotless:on
  }

  private static final class IGCallBacks implements Supplier<Flow<RequestContext>>, TriConsumer<RequestContext, String, String>, Function<RequestContext, Flow<Void>> {
    private final RequestContext requestContext
    private final Map<String, String> headers = new HashMap<>()
    private int reqStartedCount = 0
    private int reqHeaderDoneCount = 0

    IGCallBacks(RequestContext requestContext) {
      this.requestContext = requestContext
    }

    // REQUEST_STARTED
    @Override
    Flow<RequestContext> get() {
      reqStartedCount++
      return null == requestContext ? Flow.ResultFlow.empty() : new Flow.ResultFlow(requestContext)
    }

    // REQUEST_HEADER
    @Override
    void accept(RequestContext requestContext, String key, String value) {
      assert (requestContext == this.requestContext)
      headers.put(key, value)
    }

    // REQUEST_HEADER_DONE
    @Override
    Flow<Void> apply(RequestContext requestContext) {
      assert (requestContext == this.requestContext)
      reqHeaderDoneCount++
      return null
    }

    int getReqStartedCount() {
      return reqStartedCount
    }

    Map<String, String> getHeaders() {
      return headers
    }

    int getReqHeaderDoneCount() {
      return reqHeaderDoneCount
    }
  }
}
