package datadog.trace.instrumentation.springweb

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.View
import org.springframework.web.servlet.view.UrlBasedViewResolver

class ModelAndViewInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'ModelAndView constructor calls onRedirect callback'() {
    setup:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new ModelAndView(viewName)

    then:
    expected * module.onRedirect(viewName)

    when:
    new ModelAndView(viewName, Mock(Map))

    then:
    expected * module.onRedirect(viewName)

    when:
    new ModelAndView(viewName, HttpStatus.ACCEPTED)

    then:
    expected * module.onRedirect(viewName)

    when:
    new ModelAndView(viewName, Mock(Map), HttpStatus.ACCEPTED)

    then:
    expected * module.onRedirect(viewName)

    when:
    new ModelAndView(viewName, 'modelName', null)

    then:
    expected * module.onRedirect(viewName)

    where:
    viewName                                                | expected
    null                                                    | 0
    'redirected'                                            | 0
    UrlBasedViewResolver.REDIRECT_URL_PREFIX + 'redirected' | 1
    UrlBasedViewResolver.FORWARD_URL_PREFIX + 'redirected'  | 1
  }

  void 'ModelAndView not instrumented constructors'() {
    setup:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new ModelAndView()

    then:
    0 * module.onRedirect(_)

    when:
    new ModelAndView(Mock(View))

    then:
    0 * module.onRedirect(_)

    when:
    new ModelAndView(Mock(View), null)

    then:
    0 * module.onRedirect(_)

    when:
    new ModelAndView(Mock(View), 'modelName', null)

    then:
    0 * module.onRedirect(_)
  }

  void 'on set view Name calls onRedirect callback'() {
    setup:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final mv = new ModelAndView()

    when:
    mv.setViewName(viewName)

    then:
    expected * module.onRedirect(viewName)

    where:
    viewName                                                | expected
    null                                                    | 0
    'redirected'                                            | 0
    UrlBasedViewResolver.REDIRECT_URL_PREFIX + 'redirected' | 1
    UrlBasedViewResolver.FORWARD_URL_PREFIX + 'redirected'  | 1
  }
}
