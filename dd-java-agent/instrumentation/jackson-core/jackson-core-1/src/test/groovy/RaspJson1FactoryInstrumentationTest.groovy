import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import org.codehaus.jackson.JsonFactory
import org.codehaus.jackson.map.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.function.BiFunction

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.api.gateway.Events.EVENTS

class RaspJson1FactoryInstrumentationTest extends AgentTestRunner {

  @Shared
  protected static final ORIGINAL_TRACER = AgentTracer.get()

  @Shared
  @AutoCleanup
  TestHttpServer clientServer = httpServer {
    handlers {
      prefix("/json") {
        final json = '{"key":"value"}'
        response.addHeader('Content-Type', 'application/json')
        response.status(200).send(json)
      }
    }
  }

  protected traceSegment
  protected reqCtx
  protected span
  protected tracer

  void setup() {
    traceSegment = Stub(TraceSegment)
    reqCtx = Stub(RequestContext) {
      getTraceSegment() >> traceSegment
    }
    span = Stub(AgentSpan) {
      getRequestContext() >> reqCtx
    }
    tracer = Stub(AgentTracer.TracerAPI) {
      activeSpan() >> span
    }
    AgentTracer.forceRegister(tracer)
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  @Override
  protected void configurePreAgent() {
    injectSysConfig(AppSecConfig.APPSEC_ENABLED, 'true')
    injectSysConfig(AppSecConfig.APPSEC_RASP_ENABLED, 'true')
  }

  void 'test createParser(URL)'() {
    setup:
    final url = new URL("${clientServer.address}/json")
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider


    when:
    final parser = new JsonFactory().createJsonParser(url)
    parser.setCodec(new ObjectMapper())
    final json = parser.readValueAs(Map)

    then:
    parser != null
    json == [key: 'value']
    1 * callbackProvider.getCallback(EVENTS.networkConnection()) >> listener
    1 * listener.apply(reqCtx, url.toString())
  }
}
