package datadog.trace.instrumentation.thymeleaf

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context

class ThymeleafXssTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test that XssModule is called' (){
    given:
    XssModule xssModule = Mock(XssModule)
    if(hasModule){
      InstrumentationBridge.registerIastModule(xssModule)
    }
    final engine = new TemplateEngine()
    final context = new Context(Locale.getDefault(), [q:'this is vulnerable'])

    when:
    engine.process(template, context)

    then:
    expected * xssModule.onXss('this is vulnerable', template, 1)
    0 * _

    where:
    hasModule | template | expected
    false | '<span th:utext="${q}"></span>'  | 0
    true | '<span th:utext="${q}"></span>'  | 1
    true | '<span th:text="${q}"></span>'  | 0
  }
}
