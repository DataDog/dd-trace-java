package datadog.trace.instrumentation.apachehttpcore5

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.apache.hc.core5.http.HttpHost

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
  }
}
