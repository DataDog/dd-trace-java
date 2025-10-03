package datadog.trace.instrumentation.java.net

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.appsec.HttpClientRequest
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.config.IastConfig
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.SsrfModule
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import foo.bar.TestURLCallSiteSuite
import spock.lang.Shared

import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

class URLSinkCallSiteTest extends InstrumentationSpecification {

  @Shared
  protected static final ORIGINAL_TRACER = AgentTracer.get()

  @Shared
  protected static final URL = URLSinkCallSiteTest.getResource('.')

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
    injectSysConfig(IastConfig.IAST_ENABLED, 'true')
    injectSysConfig(AppSecConfig.APPSEC_ENABLED, 'true')
    injectSysConfig(AppSecConfig.APPSEC_RASP_ENABLED, 'true')
  }

  void 'test ssrf IAST endpoints, method: #suite.V1'() {
    given:
    final method = suite.getV1()
    final args = suite.getV2()
    final module = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestURLCallSiteSuite.&"$method".call(args)

    then:
    1 * module.onURLConnection(URL)

    where:
    suite << tests()
  }

  void 'test ssrf RASP endpoints, method: #suite.V1'() {
    given:
    final method = suite.getV1()
    final args = suite.getV2()
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider

    when:
    TestURLCallSiteSuite.&"$method".call(args as Object[])

    then:
    1 * callbackProvider.getCallback(EVENTS.httpClientRequest()) >> listener
    1 * listener.apply(reqCtx, _ as HttpClientRequest)

    where:
    suite << tests()
  }

  protected List<Tuple2<String, Object[]>> tests() {
    return [
      new Tuple2('openConnection', [URL]),
      new Tuple2('openConnection', [URL, Proxy.NO_PROXY]),
      new Tuple2('openStream', [URL]),
      new Tuple2('getContent', [URL]),
      new Tuple2('getContent', [URL, [Object] as Class<?>[]])
    ]
  }
}
