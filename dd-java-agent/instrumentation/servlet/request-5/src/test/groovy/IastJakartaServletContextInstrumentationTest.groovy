import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule
import datadog.trace.api.iast.sink.SessionRewritingModule

import jakarta.servlet.SessionTrackingMode


class IastJakartaServletContextInstrumentationTest extends AgentTestRunner{

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test ApplicationModule onRealPath'() {
    given:
    final module = Mock(ApplicationModule)
    InstrumentationBridge.registerIastModule(module)
    final utils = new JakartaRequestDispatcherUtils()

    when:
    utils.getRealPath("/")

    then:
    1 *  module.onRealPath(_)
    0 * _
  }

  void 'test SessionRewriting onRealPath'() {
    given:
    final module = Mock(SessionRewritingModule)
    InstrumentationBridge.registerIastModule(module)
    final sessionTrackingModes = [SessionTrackingMode.COOKIE, SessionTrackingMode.URL] as Set<SessionTrackingMode>
    final utils = new JakartaRequestDispatcherUtils(sessionTrackingModes)

    when:
    utils.getRealPath("/")

    then:
    1 *  module.checkSessionTrackingModes(['COOKIE', 'URL'] as Set<String>)
    0 * _
  }

  void 'test onRealPath'() {
    given:
    final appModule = Mock(ApplicationModule)
    InstrumentationBridge.registerIastModule(appModule)
    final sessionModule = Mock(SessionRewritingModule)
    InstrumentationBridge.registerIastModule(sessionModule)
    final sessionTrackingModes = [SessionTrackingMode.COOKIE, SessionTrackingMode.URL] as Set<SessionTrackingMode>
    final utils = new JakartaRequestDispatcherUtils(sessionTrackingModes)

    when:
    utils.getRealPath("/")

    then:
    1 *  appModule.onRealPath(_)
    1 *  sessionModule.checkSessionTrackingModes(['COOKIE', 'URL'] as Set<String>)
    0 * _
  }
}
