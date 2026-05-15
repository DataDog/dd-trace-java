package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.tt.TransactionTrackingPatterns
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter
import datadog.trace.config.inversion.ConfigHelper
import datadog.trace.test.util.DDSpecification

import java.util.function.Consumer

class HttpServerDecoratorTtExtractionTest extends DDSpecification {

  def setupSpec() {
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)
  }

  def span = Mock(AgentSpan)
  Map<String, Object> setTags = [:]

  void setup() {
    TransactionTrackingPatterns.resetForTest()
    span.setTag(_, _) >> { String k, Object v -> setTags[k] = v; null }
    span.getTag(_) >> { String k -> setTags[k] }
  }

  void cleanup() {
    TransactionTrackingPatterns.resetForTest()
  }

  def "no tag when pattern list is empty regardless of headers / qs"() {
    setup:
    def decorator = newDecorator(["X-Trace-Id", "tenant"], URI.create("http://h/p?tenant=42&debug=1"))

    when:
    decorator.onRequest(span, null, [marker: "anything"], datadog.context.Context.root())

    then:
    // Fast path: no allocation, no tag.
    setTags[InstrumentationTags.TT_EXTRACTION_SOURCES] == null
  }

  def "tags matching headers and qs with deterministic order and lowercasing"() {
    setup:
    TransactionTrackingPatterns.update(["x-trace-*", "tenant", "*-id"])
    def decorator = newDecorator(
      ["X-Trace-Id", "X-Trace-Source", "Authorization", "USER-ID"],
      URI.create("http://h/p?tenant=42&debug=1&request-id=abc"))

    when:
    decorator.onRequest(span, null, [marker: "anything"], datadog.context.Context.root())

    then:
    def csv = setTags[InstrumentationTags.TT_EXTRACTION_SOURCES]
    csv != null
    // headers first (sorted), then qs (sorted), all lowercased + deduped per bucket
    csv == "header:user-id,header:x-trace-id,header:x-trace-source,qs:request-id,qs:tenant"
  }

  def "headers only (no query string)"() {
    setup:
    TransactionTrackingPatterns.update(["x-foo"])
    def decorator = newDecorator(["X-FOO", "X-Bar"], URI.create("http://h/p"))

    when:
    decorator.onRequest(span, null, [:], datadog.context.Context.root())

    then:
    setTags[InstrumentationTags.TT_EXTRACTION_SOURCES] == "header:x-foo"
  }

  def "qs only (no header overrides)"() {
    setup:
    TransactionTrackingPatterns.update(["tenant*"])
    def decorator = newDecorator([], URI.create("http://h/p?tenantId=7&other=x"))

    when:
    decorator.onRequest(span, null, [:], datadog.context.Context.root())

    then:
    setTags[InstrumentationTags.TT_EXTRACTION_SOURCES] == "qs:tenantid"
  }

  def "no match means no tag even with non-empty patterns"() {
    setup:
    TransactionTrackingPatterns.update(["nope-*"])
    def decorator = newDecorator(["X-Foo"], URI.create("http://h/p?a=1"))

    when:
    decorator.onRequest(span, null, [:], datadog.context.Context.root())

    then:
    setTags[InstrumentationTags.TT_EXTRACTION_SOURCES] == null
  }

  def "duplicates within a bucket collapse to one entry"() {
    setup:
    TransactionTrackingPatterns.update(["x-trace-*"])
    def decorator = newDecorator(["X-Trace-Id", "x-trace-id", "X-TRACE-ID"], URI.create("http://h/p"))

    when:
    decorator.onRequest(span, null, [:], datadog.context.Context.root())

    then:
    setTags[InstrumentationTags.TT_EXTRACTION_SOURCES] == "header:x-trace-id"
  }

  def newDecorator(List<String> headerNames, URI uri) {
    return new HttpServerDecorator<Map, Map, Map, Map<String, String>>() {
        @Override
        protected TracerAPI tracer() {
          return AgentTracer.NOOP_TRACER
        }

        @Override
        protected String[] instrumentationNames() {
          ["test1", "test2"]
        }

        @Override
        protected CharSequence component() {
          "test-component"
        }

        @Override
        protected AgentPropagation.ContextVisitor<Map<String, String>> getter() {
          return ContextVisitors.stringValuesMap()
        }

        @Override
        protected AgentPropagation.ContextVisitor<Map> responseGetter() {
          null
        }

        @Override
        CharSequence spanName() {
          "http-tt-span"
        }

        @Override
        protected String method(Map m) {
          "GET"
        }

        @Override
        protected URIDataAdapter url(Map m) {
          new URIDefaultDataAdapter(uri)
        }

        @Override
        protected String peerHostIP(Map m) {
          null
        }

        @Override
        protected int peerPort(Map m) {
          0
        }

        @Override
        protected int status(Map m) {
          0
        }

        @Override
        protected String getRequestHeader(Map m, String key) {
          null
        }

        @Override
        protected void forEachRequestHeaderName(Map m, Consumer<String> consumer) {
          headerNames.each { consumer.accept(it) }
        }
      }
  }
}
