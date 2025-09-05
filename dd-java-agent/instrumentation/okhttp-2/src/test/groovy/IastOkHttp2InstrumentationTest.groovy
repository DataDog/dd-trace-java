import com.squareup.okhttp.OkHttpClient
import com.squareup.okhttp.Request
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.SsrfModule
import datadog.trace.instrumentation.okhttp2.IastHttpUrlInstrumentation
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class IastOkHttp2InstrumentationTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    // HttpUrl gets loaded early so we have to disable the advice transformer
    IastHttpUrlInstrumentation.ENABLE_ADVICE_TRANSFORMER = false
    injectSysConfig('dd.iast.enabled', 'true')
  }

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

  @Shared
  Map<?, ?> tainteds = new IdentityHashMap<>()

  void setup() {
    tainteds.clear()
    mockPropagation()
  }

  void 'test ssrf'() {
    given:
    tainteds.put(url, null)
    final module = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(module)
    final request = new Request.Builder()
      .url(url)
      .get()
      .build()

    when:
    new OkHttpClient().newCall(request).execute()

    then:
    1 * module.onURLConnection({ value -> tainteds.containsKey(value) })

    where:
    url                       | _
    server.address.toString() | _
    server.address.toURL()    | _
  }

  private void mockPropagation() {
    final Closure taint = { target, inputs ->
      if (inputs.any { input -> tainteds.containsKey(input) }) {
        tainteds.put(target, null)
      }
    }

    final propagation = Mock(PropagationModule) {
      taintStringIfTainted(*_) >> { taint(it[0], [it[1]]) }
      taintObjectIfTainted(*_) >> { taint(it[0], [it[1]]) }
    }
    InstrumentationBridge.registerIastModule(propagation)

    final codec = Mock(CodecModule) {
      onUrlCreate(*_) >> { taint(it[0], it[1] as List) }
    }
    InstrumentationBridge.registerIastModule(codec)
  }
}
