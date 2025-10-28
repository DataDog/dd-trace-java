package datadog.trace.instrumentation.apachehttpcore

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.apache.http.HttpHost

class IastHttpHostInstrumentationTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test constructor'(){
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    HttpHost.newInstance(*args)

    then:
    1 * module.taintObjectIfTainted( _ as HttpHost, 'localhost')

    where:
    args | _
    ['localhost'] | _
    ['localhost', 8080] | _
    ['localhost', 8080, 'http'] | _
  }

  void 'test toUri'(){
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    HttpHost httpHost = new HttpHost(hostname)
    String result = httpHost.toURI()

    when:
    httpHost.toURI()

    then:
    1 * module.taintObjectIfTainted(result, _ as HttpHost)

    where:
    hostname    | _
    'localhost' | _
  }
}
