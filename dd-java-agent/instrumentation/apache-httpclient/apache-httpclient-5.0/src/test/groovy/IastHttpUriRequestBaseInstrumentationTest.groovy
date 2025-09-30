import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase

class IastHttpUriRequestBaseInstrumentationTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test constructor'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    HttpUriRequestBase.newInstance(method, new URI(uri))

    then:
    1 * module.taintObjectIfTainted(_ as HttpUriRequestBase, _ as URI)
    0 * _

    where:
    method | uri
    "GET"  | 'http://localhost.com'
  }
}
