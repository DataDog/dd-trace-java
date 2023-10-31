package test.iast

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import datadog.trace.api.iast.sink.XssModule
import org.springframework.core.MethodParameter
import org.springframework.util.StreamUtils
import org.springframework.web.method.support.HandlerMethodReturnValueHandler
import org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor
import org.springframework.web.servlet.view.AbstractUrlBasedView

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class StreamUtilsInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test'(){
    setup:
    InstrumentationBridge.clearIastModules()
    final module= Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    StreamUtils.copyToString(new ByteArrayInputStream("test".getBytes()), StandardCharsets.ISO_8859_1)

    then:
    1 * module.taintIfTainted(_ as String, _ as InputStream)
    0 * _
  }
}
