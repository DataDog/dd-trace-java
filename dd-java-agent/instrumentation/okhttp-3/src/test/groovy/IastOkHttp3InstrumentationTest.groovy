import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.SsrfModule
import datadog.trace.instrumentation.okhttp3.IastHttpUrlInstrumentation
import okhttp3.OkHttpClient
import okhttp3.Request
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class IastOkHttp3InstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    // HttpUrl gets loaded early so we have to disable the advice transformer
    IastHttpUrlInstrumentation.DISABLE_ADVICE_TRANSFORMER = true
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  protected boolean isTestAgentEnabled() {
    return false
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
    final ssrf = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(ssrf)
    final request = new Request.Builder()
      .url(url)
      .get()
      .build()

    when:
    new OkHttpClient().newCall(request).execute()

    then:
    1 * ssrf.onURLConnection({ value -> tainteds.containsKey(value) })

    where:
    url                         | _
    server.address.toString()   | _
    server.address.toURL()      | _
  }

  private void mockPropagation() {
    final propagation = Mock(PropagationModule) {
      taintIfInputIsTainted(_, _) >> {
        if (tainteds.containsKey(it[1])) {
          tainteds.put(it[0], null)
        }
      }
    }
    InstrumentationBridge.registerIastModule(propagation)
  }
}
