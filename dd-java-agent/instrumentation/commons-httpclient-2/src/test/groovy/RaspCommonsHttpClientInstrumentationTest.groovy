import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.SsrfModule
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.methods.GetMethod
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.function.BiFunction

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.api.gateway.Events.EVENTS

class RaspCommonsHttpClientInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(AppSecConfig.APPSEC_ENABLED, 'true')
    injectSysConfig(AppSecConfig.APPSEC_RASP_ENABLED, 'true')
  }

  @Shared
  protected static final ORIGINAL_TRACER = AgentTracer.get()

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      prefix('/') {
        String msg = "Hello."
        response.status(200).send(msg)
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

  void 'test ssrf'() {
    setup:
    final url = server.address.toString()
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider

    when:
    def httpMethod = new GetMethod(url)
    new HttpClient().executeMethod(httpMethod)

    then:
    1 * callbackProvider.getCallback(EVENTS.networkConnection()) >> listener
    1 * listener.apply(reqCtx, url.toString())
  }

}
