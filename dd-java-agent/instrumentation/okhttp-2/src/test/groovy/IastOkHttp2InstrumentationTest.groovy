import com.squareup.okhttp.OkHttpClient
import com.squareup.okhttp.Request
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.SsrfModule
import datadog.trace.instrumentation.okhttp2.IastHttpUrlInstrumentation
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class IastOkHttp2InstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    // HttpUrl gets loaded early so we have to disable the advice transformer
    IastHttpUrlInstrumentation.DISABLE_ADVICE_TRANSFORMER = true
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
    final propagation = Mock(PropagationModule) {
      taintIfAnyInputIsTainted(_, _) >> {
        if ((it[1] as List).any { input -> tainteds.containsKey(input) }) {
          tainteds.put(it[0], null)
        }
      }
      taintIfInputIsTainted(_, _) >> {
        if (tainteds.containsKey(it[1])) {
          tainteds.put(it[0], null)
        }
      }
    }
    InstrumentationBridge.registerIastModule(propagation)
  }
}
