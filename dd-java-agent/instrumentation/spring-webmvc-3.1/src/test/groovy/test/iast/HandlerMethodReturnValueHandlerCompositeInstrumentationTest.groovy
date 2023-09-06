package test.iast

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import datadog.trace.api.iast.sink.XssModule
import org.springframework.core.MethodParameter
import org.springframework.web.method.support.HandlerMethodReturnValueHandler
import org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor
import org.springframework.web.servlet.view.AbstractUrlBasedView

class HandlerMethodReturnValueHandlerCompositeInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test HandlerMethodReturnValueHandlerComposite selectHandler instrumentation'(){
    setup:
    InstrumentationBridge.clearIastModules()
    final urModule= Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(urModule)
    final xssModule= Mock(XssModule)
    InstrumentationBridge.registerIastModule(xssModule)
    final returnType = new MethodParameter(String.getMethods()[0], -1)//Get any method parameter for testing
    final handler = isBodyProcessor? Mock(RequestResponseBodyMethodProcessor) : Mock(HandlerMethodReturnValueHandler)
    handler.supportsReturnType(_) >> true
    def composite = new HandlerMethodReturnValueHandlerComposite()
    composite = composite.addHandler(handler)
    final value = valueClass == String? 'String' : Mock(valueClass)

    when:
    composite.selectHandler(value,returnType)

    then:
    urExpected * urModule.onRedirect(_ , _, _)
    xssExpected * xssModule.onXss(_ , _, _)
    0 * urModule._
    0 * xssModule._

    where:
    valueClass | isBodyProcessor |  urExpected | xssExpected
    AbstractUrlBasedView | true | 1 | 0
    AbstractUrlBasedView | false | 1 | 0
    ModelAndView| true | 1  | 0
    ModelAndView| false | 1  | 0
    String | true | 0 | 1
    String | false | 1 | 0

  }
}
