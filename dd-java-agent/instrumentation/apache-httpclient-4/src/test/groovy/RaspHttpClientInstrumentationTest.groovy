import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.SsrfModule
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import org.apache.http.HttpHost
import org.apache.http.HttpRequest
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.BasicResponseHandler
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.message.BasicHttpRequest
import org.apache.http.protocol.BasicHttpContext
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.function.BiFunction

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.api.gateway.Events.EVENTS

class RaspHttpClientInstrumentationTest extends AgentTestRunner {

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

  void 'test ssrf httpClient execute method with args #args'() {
    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider
    final httpClient = new DefaultHttpClient()

    when:
    httpClient.execute(*args)

    then:
    1 * callbackProvider.getCallback(EVENTS.networkConnection()) >> listener
    1 * listener.apply(reqCtx, _ as String)

    where:
    args | _
    [getHttpUriRequest(server)] | _
    [getHttpUriRequest(server), new BasicHttpContext()] | _
    [getHttpUriRequest(server), new BasicResponseHandler()] | _
    [getHttpUriRequest(server), new BasicResponseHandler(), new BasicHttpContext()] | _
    [getHttpHost(server), getHttpUriRequest(server)] | _
    [getHttpHost(server), getHttpUriRequest(server), new BasicHttpContext()] | _
    [getHttpHost(server), getHttpUriRequest(server), new BasicResponseHandler()] | _
    [getHttpHost(server), getHttpUriRequest(server), new BasicResponseHandler(), new BasicHttpContext()] | _
    [getHttpHost(server), getHttpRequest(server)] | _
    [getHttpHost(server), getHttpRequest(server), new BasicHttpContext()] | _
    [getHttpHost(server), getHttpRequest(server), new BasicResponseHandler()] | _
    [getHttpHost(server), getHttpRequest(server), new BasicResponseHandler(), new BasicHttpContext()] | _
  }

  private static org.apache.http.client.methods.HttpUriRequest getHttpUriRequest(final server){
    return new HttpGet(server.address.toString())
  }

  private static HttpRequest getHttpRequest(final server){
    return new BasicHttpRequest("GET", server.address.toString())
  }

  private static HttpHost getHttpHost(final server){
    return new HttpHost(server.address.host, server.address.port, server.address.scheme)
  }
}
