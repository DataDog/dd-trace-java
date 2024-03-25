package datadog.trace.instrumentation.apachehttpcore

import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.apache.http.HttpHost

class IastHttpHostInstrumentationTest extends IastAgentTestRunner {

  void 'test'(){
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    runUnderIastTrace { HttpHost.newInstance(*args) }

    then:
    1 * module.taintIfTainted(_ as IastContext, _ as HttpHost, 'localhost')

    where:
    args                        | _
    ['localhost']               | _
    ['localhost', 8080]         | _
    ['localhost', 8080, 'http'] | _
  }
}
