package datadog.trace.instrumentation.springweb

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import org.springframework.web.servlet.view.AbstractUrlBasedView
import org.springframework.web.servlet.view.InternalResourceView
import org.springframework.web.servlet.view.RedirectView

class AbstractUrlBasedViewInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'on set url calls onRedirect callback'() {
    setup:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "https://dummy.location.com/test"

    when:
    urlBasedView.setUrl(url)

    then:
    expected * module.onRedirect(url)

    where:
    urlBasedView               | expected
    new RedirectView()         | 1
    new InternalResourceView() | 1
    Mock(AbstractUrlBasedView) | 0
  }

  void 'RedirectView constructor calls onRedirect callback'() {
    setup:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "https://dummy.location.com/test"

    when:
    new RedirectView()

    then:
    0 * module.onRedirect(_)

    when:
    new RedirectView(url)

    then:
    1 * module.onRedirect(url)
  }

  void 'InternalResourceView constructor calls onRedirect callback'() {
    setup:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "https://dummy.location.com/test"

    when:
    new InternalResourceView()

    then:
    0 * module.onRedirect(_)

    when:
    new InternalResourceView(url)

    then:
    1 * module.onRedirect(url)
  }
}

