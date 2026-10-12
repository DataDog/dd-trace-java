package datadog.trace.instrumentation.springweb

import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.appsec.AppSecContext
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import org.springframework.http.MediaType
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.mock.http.MockHttpOutputMessage
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
    def span = AgentTracer.startSpan('test', 'test-span', ctx)
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

  // Hand-written AppSecContext stub that records whether reportBlockFailure() was invoked.
  private static class RecordingAppSecContext implements AppSecContext {
    boolean blockFailureReported = false

    @Override
    boolean isManuallyKept() {
      return false
    }

    @Override
    void reportBlockFailure() {
      blockFailureReported = true
    }
  }

  // Hand-written BlockResponseFunction stub whose commit outcome is controlled by the test.
  private static class FixedOutcomeBlockResponseFunction implements BlockResponseFunction {
    private final boolean commitSucceeds

    FixedOutcomeBlockResponseFunction(boolean commitSucceeds) {
      this.commitSucceeds = commitSucceeds
    }

    @Override
    boolean tryCommitBlockingResponse(
      TraceSegment segment,
      int statusCode,
      BlockingContentType templateType,
      Map<String, String> extraHeaders,
      String securityResponseId) {
      return commitSucceeds
    }
  }

  // Activates a span with a recording appsec context, wires the given event to a callback that
  // always requests blocking, and configures the block response commit outcome. Returns the
  // recording appsec context and the activated scope so the caller can assert on the former and
  // close the latter.
  private List setupBlockFailureScenario(def event, boolean commitSucceeds) {
    def appSecContext = new RecordingAppSecContext()
    TagContext ctx = new TagContext().withRequestContextDataAppSec(appSecContext)
    def blockedSpan = AgentTracer.startSpan('test', 'test-blocked-span', ctx)
    def blockedScope = AgentTracer.activateSpan(blockedSpan)
    def blockedReqCtx = blockedSpan.spanContext() as RequestContext
    blockedReqCtx.setBlockResponseFunction(new FixedOutcomeBlockResponseFunction(commitSucceeds))
    ss.reset()
    ss.registerCallback(event, { RequestContext c, Object body ->
      new Flow.ResultFlow<Void>(null) {
          @Override
          Flow.Action getAction() {
            return new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO)
          }
        }
    } as BiFunction<RequestContext, Object, Flow<Void>>)
    [appSecContext, blockedScope]
  }

  void 'read reports block failure via stub appsec context when commit fails'() {
    given:
    def (appSecContext, blockedScope) = setupBlockFailureScenario(EVENTS.requestBodyProcessed(), false)
    def converter = new FormHttpMessageConverter()
    def raw = 'value=object'
    def message = new MockHttpInputMessage(raw.getBytes(StandardCharsets.UTF_8))
    message.headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

    when:
    converter.read(MultiValueMap, message)

    then:
    notThrown(BlockingException)
    appSecContext.blockFailureReported == true

    cleanup:
    blockedScope?.close()
  }

  void 'read does not report block failure when commit succeeds'() {
    given:
    def (appSecContext, blockedScope) = setupBlockFailureScenario(EVENTS.requestBodyProcessed(), true)
    def converter = new FormHttpMessageConverter()
    def raw = 'value=object'
    def message = new MockHttpInputMessage(raw.getBytes(StandardCharsets.UTF_8))
    message.headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

    when:
    converter.read(MultiValueMap, message)

    then:
    thrown(BlockingException)
    appSecContext.blockFailureReported == false

    cleanup:
    blockedScope?.close()
  }

  void 'write reports block failure via stub appsec context when commit fails'() {
    given:
    def (appSecContext, blockedScope) = setupBlockFailureScenario(EVENTS.responseBody(), false)
    def converter = new StringHttpMessageConverter()
    def message = new MockHttpOutputMessage()

    when:
    converter.write('example', MediaType.TEXT_PLAIN, message)

    then:
    notThrown(BlockingException)
    appSecContext.blockFailureReported == true

    cleanup:
    blockedScope?.close()
  }

  void 'write does not report block failure when commit succeeds'() {
    given:
    def (appSecContext, blockedScope) = setupBlockFailureScenario(EVENTS.responseBody(), true)
    def converter = new StringHttpMessageConverter()
    def message = new MockHttpOutputMessage()

    when:
    converter.write('example', MediaType.TEXT_PLAIN, message)

    then:
    thrown(BlockingException)
    appSecContext.blockFailureReported == false

    cleanup:
    blockedScope?.close()
  }
}
