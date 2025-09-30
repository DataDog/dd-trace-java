import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.SsrfModule
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.methods.GetMethod
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class IastCommonsHttpClientInstrumentationTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
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
    final url = server.address.toString()
    tainteds.put(url, null)
    final ssrf = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(ssrf)

    when:
    new HttpClient().executeMethod(new GetMethod(url))

    then:
    1 * ssrf.onURLConnection({ value -> tainteds.containsKey(value) })
  }

  private void mockPropagation() {
    final propagation = Mock(PropagationModule) {
      taintObjectIfTainted(_, _) >> {
        if (tainteds.containsKey(it[1])) {
          tainteds.put(it[0], null)
        }
      }
    }
    InstrumentationBridge.registerIastModule(propagation)
  }
}
