package datadog.trace.instrumentation.springweb

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import org.springframework.http.MediaType
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.util.MultiValueMap

import java.nio.charset.StandardCharsets
import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

class HttpMessageConverterInstrumentationTest extends InstrumentationSpecification {

  def scope
  def ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC)
  List<Object> publishedBodies = []

  def setup() {
    publishedBodies.clear()
    TagContext ctx = new TagContext().withRequestContextDataAppSec(new Object())
    def span = AgentTracer.startSpan('test-span', ctx)
    scope = AgentTracer.activateSpan(span)

    ss.registerCallback(EVENTS.requestBodyProcessed(), { RequestContext reqCtx, Object body ->
      publishedBodies << body
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, Object, Flow<Void>>)
  }

  def cleanup() {
    ss.reset()
    scope?.close()
  }

  void 'string http message converter does not publish parsed body event'() {
    given:
    def converter = new StringHttpMessageConverter()
    def raw = '{"value":"example"}'
    def message = new MockHttpInputMessage(raw.getBytes(StandardCharsets.UTF_8))
    message.headers.contentType = MediaType.APPLICATION_JSON

    when:
    def result = converter.read(String, message)

    then:
    result == raw
    publishedBodies.isEmpty()
  }

  void 'byte array http message converter does not publish parsed body event'() {
    given:
    def converter = new ByteArrayHttpMessageConverter()
    def raw = '{"value":"bytes"}'.getBytes(StandardCharsets.UTF_8)
    def message = new MockHttpInputMessage(raw)
    message.headers.contentType = MediaType.APPLICATION_JSON

    when:
    def result = converter.read(byte[].class, message)

    then:
    Arrays.equals(result, raw)
    publishedBodies.isEmpty()
  }

  void 'form converter continues to publish parsed body event'() {
    given:
    def converter = new FormHttpMessageConverter()
    def raw = 'value=object&another=value2'
    def message = new MockHttpInputMessage(raw.getBytes(StandardCharsets.UTF_8))
    message.headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

    when:
    def result = converter.read(MultiValueMap, message)

    then:
    result instanceof MultiValueMap
    result.getFirst('value') == 'object'
    result.getFirst('another') == 'value2'
    publishedBodies.size() == 1
    def published = publishedBodies[0] as MultiValueMap
    published.getFirst('value') == 'object'
    published.getFirst('another') == 'value2'
  }
}
