package datadog.trace.instrumentation.apachehttpcore

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.apache.http.HttpHost

class IastHttpHostInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test'(){
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
}
